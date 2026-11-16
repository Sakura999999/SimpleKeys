package com.simplecard.service;

/** Shared exact-log algorithm. Product policies keep their original 1s window and 2s TTL. */
public final class SlidingWindowScript {
    private SlidingWindowScript() {}
    // KEYS: global, peer, optional shared peer registry. ARGV: limits, unique member, window, peer budget, memory ratio.
    public static final String LUA = """
        local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000)
        local window=tonumber(ARGV[4]); local ttl=window*2
        redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-window)
        if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[1]) then return 0 end
        redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',now-window)
        if redis.call('ZCARD',KEYS[2])>=tonumber(ARGV[2]) then return 0 end
        if #KEYS==3 then
          redis.call('ZREMRANGEBYSCORE',KEYS[3],'-inf',now)
          if not redis.call('ZSCORE',KEYS[3],KEYS[2]) and redis.call('ZCARD',KEYS[3])>=tonumber(ARGV[5]) then return -1 end
          local info=redis.call('INFO','memory')
          local used=tonumber(string.match(info,'used_memory:(%d+)'))
          local max=tonumber(string.match(info,'maxmemory:(%d+)'))
          if max and max>0 and used+2048>=max*tonumber(ARGV[6]) then return -2 end
        end
        for i=1,2 do redis.call('ZADD',KEYS[i],now,ARGV[3]); redis.call('PEXPIRE',KEYS[i],ttl) end
        if #KEYS==3 then
          redis.call('ZADD',KEYS[3],now+ttl,KEYS[2])
          local old=redis.call('PTTL',KEYS[3]); redis.call('PEXPIRE',KEYS[3],math.max(old,ttl))
        end
        return 1
        """;
    /** Two related policies are checked before either is charged (short global cost + authorized order quota). */
    public static final String COMBINED = """
        local t=redis.call('TIME');local now=t[1]*1000+math.floor(t[2]/1000)
        redis.call('ZREMRANGEBYSCORE',KEYS[5],'-inf',now)
        local needed=0
        for i=1,2 do
          local g=(i-1)*2+1;local a=(i-1)*3+4;local w=tonumber(ARGV[a])
          redis.call('ZREMRANGEBYSCORE',KEYS[g],'-inf',now-w)
          if redis.call('ZCARD',KEYS[g])>=tonumber(ARGV[a+1]) then return 0 end
          redis.call('ZREMRANGEBYSCORE',KEYS[g+1],'-inf',now-w)
          if redis.call('ZCARD',KEYS[g+1])>=tonumber(ARGV[a+2]) then return 0 end
          if not redis.call('ZSCORE',KEYS[5],KEYS[g+1]) then needed=needed+1 end
        end
        if redis.call('ZCARD',KEYS[5])+needed>tonumber(ARGV[2]) then return -1 end
        local info=redis.call('INFO','memory');local max=tonumber(string.match(info,'maxmemory:(%d+)'))
        if max and max>0 and tonumber(string.match(info,'used_memory:(%d+)'))+4096>=max*tonumber(ARGV[3]) then return -2 end
        local maxTtl=0
        for i=1,2 do
          local g=(i-1)*2+1;local ttl=tonumber(ARGV[(i-1)*3+4])*2;maxTtl=math.max(maxTtl,ttl)
          for j=g,g+1 do redis.call('ZADD',KEYS[j],now,ARGV[1]);redis.call('PEXPIRE',KEYS[j],ttl) end
          redis.call('ZADD',KEYS[5],now+ttl,KEYS[g+1])
        end
        redis.call('PEXPIRE',KEYS[5],math.max(redis.call('PTTL',KEYS[5]),maxTtl));return 1
        """;
}
