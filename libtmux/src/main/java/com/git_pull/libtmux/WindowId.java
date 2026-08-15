package com.git_pull.libtmux;

/**
 * A tmux window id, such as {@code @1}.
 *
 * <p>Identifies the window itself, not where it sits. A window linked into two sessions has one id
 * and two positions, so this is what to compare when asking whether two links are the same window.
 *
 * @param value the id including its {@code @} sigil
 */
public record WindowId(String value) {

    public WindowId {
        TargetIds.require(value, '@', "window");
    }

    @Override
    public String toString() {
        return value;
    }
}
