package untitled.untitled.client;

final class ItemModelStateMachine {
    private static final int UNKNOWN_SLOT = Integer.MIN_VALUE;

    private String drawnTarget = null;
    private int lastSelectedSlot = UNKNOWN_SLOT;

    void observe(int selectedSlot, String mainHandTarget) {
        if (lastSelectedSlot == UNKNOWN_SLOT) {
            lastSelectedSlot = selectedSlot;
        } else if (selectedSlot != lastSelectedSlot) {
            drawnTarget = null;
            lastSelectedSlot = selectedSlot;
        }

        if (drawnTarget != null && !drawnTarget.equals(mainHandTarget)) {
            drawnTarget = null;
        }
    }

    void onAttack(String mainHandTarget, boolean hasDrawnState) {
        if (hasDrawnState && mainHandTarget != null && !mainHandTarget.isBlank()) {
            drawnTarget = mainHandTarget;
        }
    }

    boolean isDrawn(String targetName) {
        return drawnTarget != null && drawnTarget.equals(targetName);
    }

    void reset() {
        drawnTarget = null;
        lastSelectedSlot = UNKNOWN_SLOT;
    }
}
