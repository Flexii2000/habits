package com.fherrmann.habits.cohabit.support;

import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.push.PushTransport;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/** Ein Push-Weg, der nur mitschreibt - fuer die Tests. */
public class RecordingTransport implements PushTransport {

    public record Sent(String token, PushMessage message) {
    }

    private final String platform;
    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    /** Kennungen, die der "Dienst" ablehnt - wie ein UNREGISTERED. */
    public final Set<String> dead = new CopyOnWriteArraySet<>();

    public RecordingTransport(String platform) {
        this.platform = platform;
    }

    @Override
    public String platform() {
        return platform;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public boolean send(String deviceToken, PushMessage message) {
        sent.add(new Sent(deviceToken, message));
        return !dead.contains(deviceToken);
    }

    public List<PushMessage> to(String token) {
        return sent.stream().filter(s -> s.token().equals(token)).map(Sent::message).toList();
    }

    public List<PushMessage> ofKind(String kind) {
        return sent.stream().map(Sent::message).filter(m -> m.kind().equals(kind)).toList();
    }
}
