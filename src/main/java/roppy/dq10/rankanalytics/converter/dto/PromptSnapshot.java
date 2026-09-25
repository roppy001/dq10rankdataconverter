package roppy.dq10.rankanalytics.converter.dto;

import lombok.Data;

import java.util.List;

@Data
public class PromptSnapshot {

    private String time;

    private List<RankItem> rankList;
}
