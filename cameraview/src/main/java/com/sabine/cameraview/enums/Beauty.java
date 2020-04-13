package com.sabine.cameraview.enums;

public enum Beauty {

    BEAUTY_OFF(0x00),

    BEAUTY_MEDIUM(0x01),

    BEAUTY_HIGH(0x02);


    int value;

    Beauty(int value) {
        this.value = value;
    }

    Beauty valueOf(int value) {
        switch (value) {
            case 0x00:
                return BEAUTY_OFF;
            case 0x01:
                return BEAUTY_MEDIUM;
            case 0x02:
                return BEAUTY_HIGH;
            default:
                return BEAUTY_OFF;
        }
    }
}
