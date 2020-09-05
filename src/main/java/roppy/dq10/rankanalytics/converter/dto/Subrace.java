package roppy.dq10.rankanalytics.converter.dto;

import lombok.Data;

import java.util.List;

@Data
public class Subrace {
    private List<RankSnapshot> snapshotList;

    private List<DisplayName> displayNameList;
}
