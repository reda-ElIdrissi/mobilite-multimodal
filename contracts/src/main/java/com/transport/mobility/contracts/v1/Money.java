package com.transport.mobility.contracts.v1;

public record Money(long amountMinor, Currency currency) {
    public enum Currency { MAD }
    public Money {
        if (amountMinor < 0 || currency == null) {
            throw new IllegalArgumentException("A nonnegative MAD amount is required");
        }
    }
}
