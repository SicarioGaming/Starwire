package com.limelight.binding.input.dualsense;

/**
 * Decides what each trigger runs during one stream: the game's effect, Starwire's preset or
 * nothing.
 * <ul>
 * <li>The preset plays until the game sends its first effect in the stream. From then on only the
 * game's effects play, until the stream ends. A trigger the game hasn't set yet is off, so a
 * preset never mixes with the game's effects.</li>
 * <li>The OFF preset beats both: the triggers stay off and the game's messages are ignored.</li>
 * <li>A message changes only the triggers it names (HostTriggerMessage), as in Nonary's
 * moonlight-qt.</li>
 * </ul>
 * Strength is separate: it's the pad's own limit on trigger motor power (TriggerStrength), so it
 * applies to the game's effects and the presets alike.
 *
 * Plain Java and not thread-safe; DualSenseSession calls it on one thread.
 */
public final class TriggerRules {
    private final TriggerPreset preset;
    private boolean gameTookOver;
    private TriggerEffect gameRight = TriggerEffect.off();
    private TriggerEffect gameLeft = TriggerEffect.off();

    public TriggerRules(TriggerPreset preset) {
        this.preset = preset;
    }

    public TriggerPreset preset() {
        return preset;
    }

    /** True once the game has sent an effect in this stream (never with the OFF preset). */
    public boolean gameTookOver() {
        return gameTookOver;
    }

    /**
     * The host's 0x5503 message. Ignored with the OFF preset, and when it names neither trigger:
     * the game hasn't set one, so the preset plays on.
     */
    public void onGameMessage(HostTriggerMessage message) {
        if (preset == TriggerPreset.OFF || message == null
                || (message.triggers & (HostTriggerMessage.RIGHT_TRIGGER | HostTriggerMessage.LEFT_TRIGGER)) == 0) {
            return;
        }
        gameTookOver = true;
        if ((message.triggers & HostTriggerMessage.RIGHT_TRIGGER) != 0) {
            gameRight = message.right;
        }
        if ((message.triggers & HostTriggerMessage.LEFT_TRIGGER) != 0) {
            gameLeft = message.left;
        }
    }

    /** What the right trigger (R2) should run now. Never null. */
    public TriggerEffect right() {
        return gameTookOver ? gameRight : presetEffect();
    }

    /** What the left trigger (L2) should run now. Never null. */
    public TriggerEffect left() {
        return gameTookOver ? gameLeft : presetEffect();
    }

    /** Puts both triggers on a pad's output. It sends only what changed. */
    public void applyTo(DualSenseOutput output) {
        output.setRightTrigger(right());
        output.setLeftTrigger(left());
    }

    private TriggerEffect presetEffect() {
        TriggerEffect effect = preset.effect();
        return effect != null ? effect : TriggerEffect.off();
    }
}
