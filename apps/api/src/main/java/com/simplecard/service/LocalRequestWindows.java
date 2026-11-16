package com.simplecard.service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded, monotonic fallback. One monitor makes global+peer admission atomic within a process. */
public class LocalRequestWindows {
    private record Bucket(ArrayDeque<Long> events,long window,long[] last) {}
    private final Map<String,Bucket> buckets=new HashMap<>();
    private final int maxKeys;
    public LocalRequestWindows(int maxKeys) { this.maxKeys=maxKeys; }
    public synchronized boolean admit(String scope,String peer,long windowMs,int globalLimit,int peerLimit) {
        long now=System.nanoTime(),window=TimeUnit.MILLISECONDS.toNanos(windowMs);
        if (buckets.size()>=maxKeys) buckets.entrySet().removeIf(e->now-e.getValue().last[0]>=e.getValue().window*2);
        String g="g:"+scope+":"+windowMs,k="p:"+scope+":"+windowMs+":"+peer;
        Bucket global=buckets.get(g), per=buckets.get(k);
        trim(global,now); trim(per,now);
        if (global!=null && global.events.size()>=globalLimit || per!=null && per.events.size()>=peerLimit) return false;
        int required=(global==null?1:0)+(per==null?1:0);
        if (buckets.size()+required>maxKeys) return false;
        if (global==null) { global=new Bucket(new ArrayDeque<>(),window,new long[]{now}); buckets.put(g,global); }
        if (per==null) { per=new Bucket(new ArrayDeque<>(),window,new long[]{now}); buckets.put(k,per); }
        global.events.addLast(now);global.last[0]=now;per.events.addLast(now);per.last[0]=now;return true;
    }
    private void trim(Bucket bucket,long now) {
        if (bucket!=null) while (!bucket.events.isEmpty() && now-bucket.events.peekFirst()>=bucket.window) bucket.events.removeFirst();
    }
    public synchronized int size() { return buckets.size(); }
}
