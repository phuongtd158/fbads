package com.fbads.web;

/** { ok: true } */
public record Ok(boolean ok) {
    public static final Ok OK = new Ok(true);
}
