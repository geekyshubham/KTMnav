package app.ktmnav.backend;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable navigation guidance data model.
 */
public final class NavUpdate {

    private final String distanceText;
    private final String roadText;
    private final String etaText;
    private final String remainingDistanceText;
    private final TurnIcon turnIcon;
    private final int[] arrowPixels;

    public NavUpdate(String distanceText, String roadText, String etaText,
                     String remainingDistanceText, TurnIcon turnIcon, int[] arrowPixels) {
        this.distanceText = distanceText;
        this.roadText = roadText;
        this.etaText = etaText;
        this.remainingDistanceText = remainingDistanceText;
        this.turnIcon = turnIcon;
        this.arrowPixels = arrowPixels;
    }

    public NavUpdate withArrow(TurnIcon newIcon, int[] newPixels) {
        return new NavUpdate(distanceText, roadText, etaText, remainingDistanceText, newIcon, newPixels);
    }

    public NavUpdate withRoundaboutExit(int exitNumber) {
        String updatedRoad = NavTexts.withRoundaboutExit(roadText, exitNumber);
        if (Objects.equals(updatedRoad, roadText)) {
            return this;
        }
        return new NavUpdate(distanceText, updatedRoad, etaText, remainingDistanceText, turnIcon, arrowPixels);
    }

    public String getDistanceText() {
        return distanceText;
    }

    public String getRoadText() {
        return roadText;
    }

    public String getEtaText() {
        return etaText;
    }

    public String getRemainingDistanceText() {
        return remainingDistanceText;
    }

    public TurnIcon getTurnIcon() {
        return turnIcon;
    }

    public int[] getArrowPixels() {
        return arrowPixels;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof NavUpdate)) return false;
        NavUpdate other = (NavUpdate) obj;
        return Objects.equals(distanceText, other.distanceText)
                && Objects.equals(roadText, other.roadText)
                && Objects.equals(etaText, other.etaText)
                && Objects.equals(remainingDistanceText, other.remainingDistanceText)
                && turnIcon == other.turnIcon
                && Arrays.equals(arrowPixels, other.arrowPixels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(distanceText, roadText, etaText, remainingDistanceText, turnIcon, Arrays.hashCode(arrowPixels));
    }

    @Override
    public String toString() {
        return "NavUpdate{distance=" + distanceText + ", road=" + roadText
                + ", eta=" + etaText + ", remaining=" + remainingDistanceText
                + ", turnIcon=" + turnIcon
                + ", arrowPixels=" + (arrowPixels == null ? "none" : arrowPixels.length + "px") + "}";
    }
}
