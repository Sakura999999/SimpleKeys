package com.simplecard.service;

import com.simplecard.constant.ErrorCode;
import com.simplecard.exception.BusinessException;
import java.util.*;

public final class PublicRequestBounds {
    private PublicRequestBounds() {}
    public static void pagination(int page,int size,String keyword) {
        if(page<1 || page>10000 || size<1 || size>100 || keyword!=null && keyword.length()>64) throw bad();
    }
    public static List<String> strings(Map<String,?> request,String field,int max,int length) {
        Object raw=request.get(field);if(raw==null)return List.of();
        if(!(raw instanceof List<?> list) || list.size()>max)throw bad();
        List<String> result=new ArrayList<>();
        for(Object item:list) {
            if(!(item instanceof String value) || value.isBlank() || value.length()>length)throw bad();
            result.add(value.trim());
        }
        return result;
    }
    public static BusinessException bad() { return new BusinessException(ErrorCode.BAD_REQUEST,"请求参数超出允许范围"); }
}
