package com.fherrmann.habits.dto;

/** Anlegen ({@code id} optional, von der App fuer den Postausgang) oder umbenennen. */
public record FocusCategoryRequest(String id, String name) {
}
