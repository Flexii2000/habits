package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.AbstinenceConfig;
import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.model.ChallengeConfig;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.GoalConfig;
import com.fherrmann.habits.cohabit.model.HealthConfig;
import com.fherrmann.habits.cohabit.model.StreakConfig;
import com.fherrmann.habits.cohabit.model.Tracking;

import java.util.List;

/** {@code CohabitConfig} beim Anlegen (mit {@code invitePersonIds}) und Bearbeiten. */
public record CohabitInput(CohabitType type, String name, String color, String timezone, Tracking tracking,
                           Boolean photoRequired, Integer backfillHours, String reminderTime,
                           Boolean membersCanInvite, StreakConfig streak, AbstinenceConfig abstinence,
                           GoalConfig goal, ChallengeConfig challenge, HealthConfig health, AutoConfig auto,
                           List<String> invitePersonIds) {
}
