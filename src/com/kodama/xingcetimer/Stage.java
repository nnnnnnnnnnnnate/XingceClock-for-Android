package com.kodama.xingcetimer;

/** One countdown entry: a name and a duration in minutes. */
public class Stage {
    public final String name;
    public final double minutes;

    public Stage(String name, double minutes) {
        this.name = name;
        this.minutes = minutes;
    }
}
