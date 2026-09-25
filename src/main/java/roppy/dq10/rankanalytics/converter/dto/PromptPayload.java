package roppy.dq10.rankanalytics.converter.dto;

import lombok.Data;

import java.util.List;

@Data
public class PromptPayload {

    private String raceKey;

    private int round;

    private int subraceIndex;

    private int[] borders;

    private List<PromptSnapshot> snapshots;
}
