package app.ktmnav.backend;

/**
 * Exact turn icon binary enum matching bike instrument cluster protocol.
 * Based on OpenDash / TurnIt protocol evidence.
 */
public enum TurnIcon {
    UNKNOWN(0),
    UNDEFINED(1),
    GO_STRAIGHT(2),
    UTURN_RIGHT(3),
    UTURN_LEFT(4),
    KEEP_RIGHT(5),
    LIGHT_RIGHT(6),
    QUITE_RIGHT(7),
    HEAVY_RIGHT(8),
    KEEP_MIDDLE(9),
    KEEP_LEFT(10),
    LIGHT_LEFT(11),
    QUITE_LEFT(12),
    HEAVY_LEFT(13),
    ENTER_HIGHWAY_RIGHT_LANE(14),
    ENTER_HIGHWAY_LEFT_LANE(15),
    LEAVE_HIGHWAY_RIGHT_LANE(16),
    LEAVE_HIGHWAY_LEFT_LANE(17),
    HIGHWAY_KEEP_RIGHT(18),
    HIGHWAY_KEEP_LEFT(19),
    START(20),
    END(21),
    FERRY(22),
    PASS_STATION(23),
    HEAD_TO(24),
    CHANGE_LINE(25),
    RAB_SECT_1_RH(26),
    RAB_SECT_2_RH(27),
    RAB_SECT_3_RH(28),
    RAB_SECT_4_RH(29),
    RAB_SECT_5_RH(30),
    RAB_SECT_6_RH(31),
    RAB_SECT_7_RH(32),
    RAB_SECT_8_RH(33),
    RAB_SECT_9_RH(34),
    RAB_SECT_10_RH(35),
    RAB_SECT_11_RH(36),
    RAB_SECT_12_RH(37),
    RAB_SECT_13_RH(38),
    RAB_SECT_14_RH(39),
    RAB_SECT_15_RH(40),
    RAB_SECT_16_RH(41),
    RAB_SECT_1_LH(42),
    RAB_SECT_2_LH(43),
    RAB_SECT_3_LH(44),
    RAB_SECT_4_LH(45),
    RAB_SECT_5_LH(46),
    RAB_SECT_6_LH(47),
    RAB_SECT_7_LH(48),
    RAB_SECT_8_LH(49),
    RAB_SECT_9_LH(50),
    RAB_SECT_10_LH(51),
    RAB_SECT_11_LH(52),
    RAB_SECT_12_LH(53),
    RAB_SECT_13_LH(54),
    RAB_SECT_14_LH(55),
    RAB_SECT_15_LH(56),
    RAB_SECT_16_LH(57);

    private final int binary;

    TurnIcon(int binary) {
        this.binary = binary;
    }

    public int getBinary() {
        return binary;
    }

    public static TurnIcon fromBinary(int binary) {
        for (TurnIcon icon : values()) {
            if (icon.binary == binary) {
                return icon;
            }
        }
        return UNKNOWN;
    }
}
