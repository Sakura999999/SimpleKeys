package com.simplecard.service;

import java.util.Map;
import java.util.function.*;

public final class BoundedBuckets {
    private BoundedBuckets() {}
    public static <T> T get(Map<String,T> buckets,String key,int capacity,Supplier<T> create,Predicate<T> expired) {
        synchronized(buckets) {
            T existing=buckets.get(key);if(existing!=null)return existing;
            if(buckets.size()>=capacity)buckets.entrySet().removeIf(e->expired.test(e.getValue()));
            if(buckets.size()>=capacity)return null;
            T added=create.get();buckets.put(key,added);return added;
        }
    }
}
