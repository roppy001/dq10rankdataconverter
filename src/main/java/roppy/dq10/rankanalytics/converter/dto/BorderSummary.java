package roppy.dq10.rankanalytics.converter.dto;

import lombok.Data;

// ボーダーごとのAI解説文。SubraceのaiSummaryはこれの配列となる
@Data
public class BorderSummary {

    private int border;

    private String borderName;

    private String summary;
}
