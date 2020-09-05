package roppy.dq10.rankanalytics.converter.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
public class RankItem {

    private int rank;

    private int point;

    private String name;

    @JsonIgnore
    private String extraData;

    @JsonIgnore
    private boolean anonymous;

    private int id;
}
