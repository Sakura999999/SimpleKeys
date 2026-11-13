package com.simplecard.service;

import com.simplecard.config.ProductGuardProperties;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;

/** All cache keys live on the dedicated cache Redis, never on the order-lock client. */
@Component
public class ProductProtectionStore {
    private final ObjectProvider<RedissonClient> clients;
    private final ProductGuardProperties p;
    private volatile long unavailableUntil;
    public ProductProtectionStore(@Qualifier("productGuardRedis") ObjectProvider<RedissonClient> clients,
                                  ProductGuardProperties p) { this.clients = clients; this.p = p; }
    String key(String suffix) { return p.getPrefix() + ":{product-guard}:" + suffix; }
    private RedissonClient client() {
        if (System.nanoTime() < unavailableUntil) throw new IllegalStateException("Product cache circuit open");
        try { return clients.getObject(); }
        catch (RuntimeException e) { if (!capacityError(e)) unavailableUntil = System.nanoTime() + 2_000_000_000L; throw e; }
    }
    private <T> T eval(String script, RScript.ReturnType type, List<Object> keys, Object... args) {
        // A rejected probe must not extend the open circuit forever under continuous traffic.
        RedissonClient connection = client();
        try { return connection.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, script, type, keys, args); }
        catch (RuntimeException e) { if (!capacityError(e)) unavailableUntil = System.nanoTime() + 2_000_000_000L; throw e; }
    }
    public static boolean capacityError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && (message.contains("OOM") || message.contains("maxmemory"))) return true;
        }
        return false;
    }
    private <T> T readEval(String script, RScript.ReturnType type, List<Object> keys, Object... args) {
        RedissonClient connection = client();
        try { return connection.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY,
                "#!lua flags=no-writes\n" + script, type, keys, args); }
        catch (RuntimeException e) { if (!capacityError(e)) unavailableUntil = System.nanoTime() + 2_000_000_000L; throw e; }
    }
    // Redis restart invalidates readiness even if an older AOF/snapshot restores the active pointer.
    private static final String RUN_ID = "local info=redis.call('INFO','server'); local run=string.match(info,'run_id:([^\\r\\n]+)'); ";
    public String snapshot() {
        return readEval(RUN_ID + "local a=redis.call('GET',KEYS[1]); if a and string.sub(a,1,string.len(run)+1)==run..'|' then return a end; return nil",
                RScript.ReturnType.VALUE, List.of(key("active")));
    }
    public String runId() { return readEval(RUN_ID + "return run", RScript.ReturnType.VALUE, List.of()); }
    private String rawSnapshot() {
        return readEval("return redis.call('GET',KEYS[1])", RScript.ReturnType.VALUE, List.of(key("active")));
    }
    private String version(String snapshot) { return snapshot.substring(snapshot.indexOf('|') + 1); }
    public RBloomFilter<String> bloom(String snapshot) {
        return client().getBloomFilter(key("bloom:" + version(snapshot)), StringCodec.INSTANCE);
    }
    public boolean dataExists(String snapshot) {
        // RBloomFilter.isExists() also counts its config key, so it cannot detect missing bit data.
        String data = bloom(snapshot).getName();
        return readEval("return redis.call('EXISTS',KEYS[1],KEYS[2])", RScript.ReturnType.INTEGER,
                List.of(data, data + ":config")).equals(2L);
    }
    public boolean canReject(String snapshot, UUID id) {
        String data = bloom(snapshot).getName();
        // These checks must be ONE atomic operation: a switch may clear dirty IDs between separate reads.
        return readEval(RUN_ID + "if redis.call('GET',KEYS[1])~=ARGV[1] or redis.call('HEXISTS',KEYS[2],ARGV[2])==1 " +
                        "or redis.call('EXISTS',KEYS[3],KEYS[4])~=2 or string.sub(ARGV[1],1,string.len(run)+1)~=run..'|' then return 0 end; return 1",
                RScript.ReturnType.INTEGER, List.of(key("active"), key("dirty"), data, data + ":config"),
                snapshot, id.toString()).equals(1L);
    }
    /** Dirty IDs bypass negative caches AND Bloom until a completed rebuild incorporates them. */
    public boolean dirty(UUID id) {
        return readEval("return redis.call('HEXISTS',KEYS[1],ARGV[1])", RScript.ReturnType.INTEGER,
                List.of(key("dirty")), id.toString()).equals(1L);
    }
    public void beginMutation(UUID id, String token) {
        eval("redis.call('SET',KEYS[3],ARGV[2]); redis.call('HSET',KEYS[1],ARGV[2],ARGV[1]); redis.call('HINCRBY',KEYS[2],ARGV[1],1); return 1",
                RScript.ReturnType.INTEGER, List.of(key("pending"), key("dirty"), key("display-epoch")), id.toString(), token);
    }
    public void completeMutation(UUID id, String token) {
        eval("if redis.call('HGET',KEYS[1],ARGV[2])==ARGV[1] then redis.call('HDEL',KEYS[1],ARGV[2]); redis.call('HSET',KEYS[2],ARGV[2],ARGV[1]) end; return 1",
                RScript.ReturnType.INTEGER, List.of(key("pending"), key("completed")), id.toString(), token);
    }
    public Map<String, String> completedMutations() {
        return client().<String,String>getMap(key("completed"), StringCodec.INSTANCE).readAllMap();
    }
    /** Caller holds the DB publication barrier: no product transaction can still own these tokens. */
    public void recoverPending() {
        eval("local all=redis.call('HGETALL',KEYS[1]); for i=1,#all,2 do " +
                        "redis.call('HSET',KEYS[2],all[i],all[i+1]); redis.call('HDEL',KEYS[1],all[i]) end; return #all/2",
                RScript.ReturnType.INTEGER, List.of(key("pending"), key("completed")));
    }
    public boolean negative(String snapshot, UUID id) {
        return readEval("if redis.call('GET',KEYS[1])~=ARGV[1] or redis.call('HEXISTS',KEYS[2],ARGV[2])==1 then return 0 end; " +
                        "local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); " +
                        "local score=redis.call('ZSCORE',KEYS[3],ARGV[2]); return score and tonumber(score)>now and 1 or 0", RScript.ReturnType.INTEGER,
                List.of(key("active"), key("dirty"), key("negative:" + version(snapshot))), snapshot, id.toString()).equals(1L);
    }
    public void cacheNegative(String snapshot, UUID id, int seconds) {
        eval("if redis.call('GET',KEYS[1])~=ARGV[1] or redis.call('HEXISTS',KEYS[2],ARGV[2])==1 then return 0 end; " +
                        "local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); redis.call('ZREMRANGEBYSCORE',KEYS[3],'-inf',now); " +
                        "if redis.call('ZCARD',KEYS[3])>=tonumber(ARGV[4]) then return 0 end; " +
                        "redis.call('ZADD',KEYS[3],now+tonumber(ARGV[3])*1000,ARGV[2]); redis.call('EXPIRE',KEYS[3],120); return 1",
                RScript.ReturnType.INTEGER, List.of(key("active"), key("dirty"), key("negative:" + version(snapshot))),
                snapshot, id.toString(), seconds, p.getMaxNegativeEntries());
    }
    /** Exact sliding windows; peer keys are allocated only after global admission, bounding key growth. */
    public boolean admit(String scope, String peer, int globalLimit, int peerLimit) {
        String member = UUID.randomUUID().toString();
        return eval("local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); " +
                        "for i=1,2 do redis.call('ZREMRANGEBYSCORE',KEYS[i],'-inf',now-1000) end; " +
                        "if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[1]) or redis.call('ZCARD',KEYS[2])>=tonumber(ARGV[2]) then return 0 end; " +
                        "for i=1,2 do redis.call('ZADD',KEYS[i],now,ARGV[3]); redis.call('PEXPIRE',KEYS[i],2000) end; return 1",
                RScript.ReturnType.INTEGER, List.of(key("rate:" + scope), key("rate:" + scope + ":" + peer)),
                globalLimit, peerLimit, member).equals(1L);
    }
    public RLock rebuildLock() { return client().getLock(key("rebuild-lock")); }
    public RLock displayLock(UUID id) { return client().getLock(key("display-lock:" + id)); }

    private static final String DISPLAY_VALID = RUN_ID +
            "local a=redis.call('GET',KEYS[1]); local e=redis.call('GET',KEYS[3]) or '0'; " +
            "if not a or run..'@'..e~=ARGV[1] or string.sub(a,1,string.len(run)+1)~=run..'|' " +
            "or redis.call('HEXISTS',KEYS[2],ARGV[2])==1 then ";
    public String displayContext(UUID id) {
        return readEval(RUN_ID + "local a=redis.call('GET',KEYS[1]); if not a or string.sub(a,1,string.len(run)+1)~=run..'|' " +
                        "or redis.call('HEXISTS',KEYS[2],ARGV[1])==1 then return nil end; return run..'@'..(redis.call('GET',KEYS[3]) or '0')",
                RScript.ReturnType.VALUE, List.of(key("active"), key("dirty"), key("display-epoch")), id.toString());
    }
    private List<Object> displayKeys() {
        return List.of(key("active"), key("dirty"), key("display-epoch"), key("display-values"), key("display-expiry"));
    }
    public String readDisplay(UUID id, String context) {
        return readEval(DISPLAY_VALID + "return nil end; " +
                        "local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); local expires=redis.call('ZSCORE',KEYS[5],ARGV[2]); " +
                        "if not expires or tonumber(expires)<=now then return nil end; local value=redis.call('HGET',KEYS[4],ARGV[2]); " +
                        "local prefix=ARGV[1]..'\\n'; if value and string.sub(value,1,string.len(prefix))==prefix then return string.sub(value,string.len(prefix)+1) end; return nil",
                RScript.ReturnType.VALUE, displayKeys(), context, id.toString());
    }
    private static final String PRUNE_DISPLAY = "local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); " +
            "local ids=redis.call('ZRANGEBYSCORE',KEYS[5],'-inf',now,'LIMIT',0,2000); " +
            "for _,id in ipairs(ids) do redis.call('HDEL',KEYS[4],id); redis.call('ZREM',KEYS[5],id) end; ";
    public void pruneDisplay() {
        // This script only frees memory and must remain executable while Redis is full.
        eval("#!lua flags=allow-oom\n" + PRUNE_DISPLAY + "return #ids", RScript.ReturnType.INTEGER, displayKeys());
    }
    public List<Long> displayMemory() {
        return readEval("local info=redis.call('INFO','memory'); return {tonumber(string.match(info,'used_memory:(%d+)')), " +
                        "tonumber(string.match(info,'maxmemory:(%d+)')), (redis.call('MEMORY','USAGE',KEYS[1],'SAMPLES',0) or 0)+" +
                        "(redis.call('MEMORY','USAGE',KEYS[2],'SAMPLES',0) or 0),redis.call('HLEN',KEYS[1])}",
                RScript.ReturnType.MULTI,List.of(key("display-values"),key("display-expiry")));
    }
    /** Global quotas cover all API replicas. Memory overhead is counted with MEMORY USAGE, not payload size alone. */
    public long writeDisplay(UUID id, String context, String json, int ttlSeconds) {
        return eval(DISPLAY_VALID + "return 0 end; " + PRUNE_DISPLAY +
                        "if string.len(ARGV[3])>tonumber(ARGV[5]) then return -2 end; " +
                        "local info=redis.call('INFO','memory'); local used=tonumber(string.match(info,'used_memory:(%d+)')); " +
                        "local max=tonumber(string.match(info,'maxmemory:(%d+)')); if max and max>0 and used+string.len(ARGV[3])+512>=max*tonumber(ARGV[8]) then return -3 end; " +
                        "if redis.call('HEXISTS',KEYS[4],ARGV[2])==0 and redis.call('HLEN',KEYS[4])>=tonumber(ARGV[6]) then return -1 end; " +
                        "local size=(redis.call('MEMORY','USAGE',KEYS[4],'SAMPLES',0) or 0)+(redis.call('MEMORY','USAGE',KEYS[5],'SAMPLES',0) or 0); " +
                        "local value=ARGV[1]..'\\n'..ARGV[3]; if size+string.len(value)+512>tonumber(ARGV[7]) then return -1 end; " +
                        "redis.call('ZADD',KEYS[5],now+tonumber(ARGV[4])*1000,ARGV[2]); redis.call('HSET',KEYS[4],ARGV[2],value); " +
                        "redis.call('EXPIRE',KEYS[4],840); redis.call('EXPIRE',KEYS[5],840); return 1",
                RScript.ReturnType.INTEGER, displayKeys(), context, id.toString(), json, ttlSeconds,
                p.getDisplayMaxEntryBytes(), p.getDisplayMaxEntries(), p.getDisplayMaxBytes(), p.getDisplayMemoryStopRatio());
    }
    public void claimBuild(String token) {
        eval("redis.call('SET',KEYS[1],ARGV[1]); return 1", RScript.ReturnType.INTEGER, List.of(key("builder")), token);
    }
    public boolean publish(String snapshot, String token, Map<String,String> completed) {
        List<Object> args = new ArrayList<>(List.of(snapshot, token));
        completed.forEach((id, value) -> { args.add(id); args.add(value); });
        String data = bloom(snapshot).getName();
        String old = rawSnapshot();
        String oldData = old == null ? data : bloom(old).getName();
        return eval(RUN_ID + "if redis.call('GET',KEYS[2])~=ARGV[2] or string.sub(ARGV[1],1,string.len(run)+1)~=run..'|' then return 0 end; " +
                        "if redis.call('EXISTS',KEYS[5],KEYS[6])~=2 then return 0 end; " +
                        "for i=3,#ARGV,2 do if redis.call('HGET',KEYS[3],ARGV[i])==ARGV[i+1] then " +
                        "local c=tonumber(redis.call('HGET',KEYS[4],ARGV[i+1])); if not c or c<1 then return 0 end end end; " +
                        "redis.call('PERSIST',KEYS[5]); redis.call('PERSIST',KEYS[6]); " +
                        "if KEYS[5]~=KEYS[7] then redis.call('EXPIRE',KEYS[7],3600); redis.call('EXPIRE',KEYS[8],3600) end; " +
                        "redis.call('SET',KEYS[1],ARGV[1]); for i=3,#ARGV,2 do " +
                        "if redis.call('HGET',KEYS[3],ARGV[i])==ARGV[i+1] then redis.call('HDEL',KEYS[3],ARGV[i]); if redis.call('HINCRBY',KEYS[4],ARGV[i+1],-1)<=0 then redis.call('HDEL',KEYS[4],ARGV[i+1]) end end end; return 1",
                // Redisson 3.52 stores Bloom configuration at <name>:config; integration tests cover both keys.
                RScript.ReturnType.INTEGER, List.of(key("active"), key("builder"), key("completed"), key("dirty"),
                        data, data + ":config", oldData, oldData + ":config"), args.toArray()).equals(1L);
    }
    public void retireIfUnpublished(String snapshot) {
        String data=bloom(snapshot).getName();
        eval("#!lua flags=allow-oom\nif redis.call('GET',KEYS[1])~=ARGV[1] then redis.call('EXPIRE',KEYS[2],7200); redis.call('EXPIRE',KEYS[3],7200) end; return 1",
                RScript.ReturnType.INTEGER,List.of(key("active"),data,data+":config"),snapshot);
    }
}
