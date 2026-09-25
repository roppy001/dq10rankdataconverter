package roppy.dq10.rankanalytics.converter.dto;

import lombok.Data;

// レース+回ごとのボーダー順位定義(rankanalytics.jsのRACE_CONFIG_MAPから抽出したもの)
@Data
public class BorderDefinition {

    private int rank;

    private String borderName;
}
