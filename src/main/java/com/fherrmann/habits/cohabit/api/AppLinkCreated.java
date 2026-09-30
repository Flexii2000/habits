package com.fherrmann.habits.cohabit.api;

/** Der Token steht nur in dieser Antwort - gespeichert wird allein sein Hash. */
public record AppLinkCreated(String id, String label, String setupUrl, String token) {
}
