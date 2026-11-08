package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.SkillValidationRepository;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Renews the database lease while local archive validation is active. */
public final class SkillValidationExecution {
    private final SkillValidationRepository repository;
    private final SkillValidationService service;
    private final ScheduledExecutorService timers;
    public SkillValidationExecution(SkillValidationRepository repository, SkillValidationService service, ScheduledExecutorService timers) {
        this.repository = Objects.requireNonNull(repository); this.service = Objects.requireNonNull(service); this.timers = Objects.requireNonNull(timers);
    }
    public void run(SkillValidationRepository.Command command) {
        var active = new AtomicBoolean(true);
        var renewal = timers.scheduleAtFixedRate(() -> {
            try { repository.guard(command); } catch (RuntimeException lost) { active.set(false); }
        }, 10, 10, TimeUnit.SECONDS);
        try { service.validate(command, active::get); }
        finally { active.set(false); renewal.cancel(false); }
    }
}
