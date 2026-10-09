package com.fherrmann.habits.cohabit.api;

import com.fherrmann.habits.cohabit.model.HealthMetric;
import com.fherrmann.habits.cohabit.model.ValueUnit;

import java.util.List;

/**
 * Ein Co-Habit als Tagesreihe.
 *
 * @param kind            {@code BINARY} (1 getan, 0 nicht) oder {@code AMOUNT} (eine Menge in {@code unit})
 * @param unavailableText die Quelle eines automatischen Co-Habits antwortete nicht - dann ist
 *                        {@code days} leer, und nur dieses Co-Habit fehlt
 * @param days            nur Tage, an denen das Co-Habit fuer die Person galt, aufsteigend;
 *                        ein fehlender Tag ist "unbekannt", nicht 0
 */
public record DaySeriesView(CohabitRef ref, boolean archived, String kind, ValueUnit unit, String unitLabel,
                            HealthMetric healthMetric, String unavailableText, List<DayValueView> days) {
}
