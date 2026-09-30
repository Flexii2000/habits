package com.fherrmann.habits.cohabit.api;

import com.fherrmann.habits.cohabit.model.AbstinenceConfig;
import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.model.ChallengeConfig;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.GoalConfig;
import com.fherrmann.habits.cohabit.model.HealthConfig;
import com.fherrmann.habits.cohabit.model.StreakConfig;
import com.fherrmann.habits.cohabit.model.Tracking;

public record CohabitConfigView(CohabitType type, String name, String color, String timezone, Tracking tracking,
                                boolean photoRequired, int backfillHours, String reminderTime,
                                boolean membersCanInvite, StreakConfig streak, AbstinenceConfig abstinence,
                                GoalConfig goal, ChallengeConfig challenge, HealthConfig health, AutoConfig auto) {

    public static CohabitConfigView of(Cohabit c) {
        return new CohabitConfigView(c.type, c.name, c.color, c.timezone, c.tracking, c.photoRequired,
                c.backfillHours, c.reminderTime, c.membersCanInvite, c.streak, c.abstinence, c.goal, c.challenge,
                c.health, c.auto);
    }
}
