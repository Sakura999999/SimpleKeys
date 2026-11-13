package com.simplecard.exception;
public class PaymentRejectedException extends BusinessException {
    public PaymentRejectedException(int code, String message) { super(code,message); }
}
