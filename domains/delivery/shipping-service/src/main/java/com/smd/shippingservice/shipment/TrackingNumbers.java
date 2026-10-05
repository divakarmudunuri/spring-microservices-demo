package com.smd.shippingservice.shipment;

import java.security.SecureRandom;

/** Generates tracking numbers like {@code SMD7K2Q9XW4PJ3}: unambiguous characters, unique by constraint. */
final class TrackingNumbers {

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();   // no 0/O, 1/I/L
    private static final SecureRandom RANDOM = new SecureRandom();

    private TrackingNumbers() {
    }

    static String next() {
        StringBuilder number = new StringBuilder("SMD");
        for (int i = 0; i < 11; i++) {
            number.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return number.toString();
    }
}
