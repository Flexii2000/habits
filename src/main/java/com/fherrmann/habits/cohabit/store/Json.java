package com.fherrmann.habits.cohabit.store;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Wie Zahlen und Dateien geschrieben werden.
 *
 * <p>Ganze Kommazahlen gehen als ganze Zahlen raus ({@code 8200} statt
 * {@code 8200.0}): die Apps lesen manche Felder als Ganzzahl, und ein
 * {@code .0} laesst dort das Dekodieren scheitern. Umgekehrt liest jeder Client
 * eine {@code 8200} problemlos in ein Kommazahl-Feld.
 */
public final class Json {

    private Json() {
    }

    public static SimpleModule wholeNumbers() {
        SimpleModule module = new SimpleModule("cohabit-whole-numbers");
        module.addSerializer(Double.class, new WholeDoubleSerializer());
        module.addSerializer(Double.TYPE, new WholeDoubleSerializer());
        return module;
    }

    /** Der Mapper fuer die Dateien unter {@code data/cohabit/}. */
    public static JsonMapper fileMapper() {
        return JsonMapper.builder()
                .addModule(wholeNumbers())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    static final class WholeDoubleSerializer extends StdSerializer<Double> {

        WholeDoubleSerializer() {
            super(Double.class);
        }

        @Override
        public void serialize(Double value, JsonGenerator gen, SerializationContext ctxt) {
            double v = value;
            if (!Double.isInfinite(v) && v == Math.rint(v) && Math.abs(v) < 1e15) {
                gen.writeNumber((long) v);
            } else {
                gen.writeNumber(v);
            }
        }
    }
}
