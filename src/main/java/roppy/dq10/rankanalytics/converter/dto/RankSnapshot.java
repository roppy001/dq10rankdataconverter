package roppy.dq10.rankanalytics.converter.dto;

import lombok.Data;

import java.util.List;

@Data
public class RankSnapshot {

    private String timeString;

    private List<RankItem> rankList;
}
