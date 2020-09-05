package roppy.dq10.rankanalytics.converter;

import com.amazonaws.SdkClientException;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import roppy.dq10.rankanalytics.converter.dto.Race;
import roppy.dq10.rankanalytics.converter.dto.RankItem;
import roppy.dq10.rankanalytics.converter.dto.RankSnapshot;
import roppy.dq10.rankanalytics.converter.dto.Subrace;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class S3Downloader {
    private static final S3Downloader INSTANCE = new S3Downloader();
    private S3Downloader(){}
    public static S3Downloader getInstance(){return INSTANCE;}

    private static final String BUCKET_NAME = "roppyracedata";
    private static final int MAX_KEY_NUMBER = 200;

    private static final String ANONYMOUS_NAME = "（ないしょ）";

    public Race download(String prefix,int round) throws S3Exception,IllegalDataException{
        Race race = new Race();
        race.setSubraceList(new ArrayList<>());

        try {
            // 最新のデータを検索する

            String stringObjKeySearchKey = String.format("%s/%d/", prefix, round);

            AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                    .withRegion(Regions.AP_NORTHEAST_1)
                    .build();

            ListObjectsV2Request req = new ListObjectsV2Request()
                    .withBucketName(BUCKET_NAME)
                    .withPrefix(stringObjKeySearchKey)
                    .withMaxKeys(MAX_KEY_NUMBER);
            ListObjectsV2Result result;
            List<String> keyList = new ArrayList<>();

            do {
                result = s3Client.listObjectsV2(req);

                for (S3ObjectSummary objectSummary : result.getObjectSummaries()) {
                    keyList.add(objectSummary.getKey());
                }

                String token = result.getNextContinuationToken();
                req.setContinuationToken(token);
            } while (result.isTruncated());

            Collections.sort(keyList);

            for(String stringObjKey : keyList){
                // 最新のデータを読み込む
                S3Object obj = s3Client.getObject(BUCKET_NAME,stringObjKey);


                try(InputStream is =  obj.getObjectContent();
                    InputStreamReader isr = new InputStreamReader(is);
                    BufferedReader br = new BufferedReader(isr)){

                    String line = br.readLine();

                    if(line == null){
                        throw new IllegalDataException();
                    }

                    String [] firstTokens = line.split("\t",-1);

                    if(firstTokens.length <= 1){
                        throw new IllegalDataException();
                    }

                    int subRaceNumber = Integer.parseInt(firstTokens[0]);

                    String targetDateTimeString = LocalDateTime.parse(firstTokens[1],
                            DateTimeFormatter.ofPattern("yyyyMMddHH"))
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

                    if(race.getSubraceList().isEmpty()) {
                        for(int i=0;i<subRaceNumber;i++) {
                            Subrace r = new Subrace();
                            r.setSnapshotList(new ArrayList<>());
                            race.getSubraceList().add(r);
                        }
                    }

                    List<RankSnapshot> snapshotList = new ArrayList<>();
                    for(int i=0;i<subRaceNumber;i++) {
                        RankSnapshot s = new RankSnapshot();
                        s.setTimeString(targetDateTimeString);
                        s.setRankList(new ArrayList<>());
                        snapshotList.add(s);
                    }


                    while((line = br.readLine()) != null){
                        String [] splittedLine = line.split("\t",-1);
                        if(splittedLine.length != 5) {
                            throw new IllegalDataException();
                        }

                        int subraceIndex = Integer.parseInt(splittedLine[0]);
                        if(subraceIndex < 0 || subraceIndex >= subRaceNumber){
                            throw new IllegalDataException();
                        }

                        RankItem item = new RankItem();
                        item.setRank(Integer.parseInt(splittedLine[1]));
                        item.setPoint(Integer.parseInt(splittedLine[2]));
                        item.setName(splittedLine[3]);
                        item.setExtraData(splittedLine[4]);

                        item.setAnonymous(ANONYMOUS_NAME.equals(item.getName()));

                        snapshotList.get(subraceIndex).getRankList().add(item);
                    }

                    for(int i=0;i<subRaceNumber;i++) {
                        race.getSubraceList().get(i).getSnapshotList().add(snapshotList.get(i));
                    }

                }catch (NumberFormatException ioe) {
                    throw new IllegalDataException();
                }catch (IOException ioe) {
                    throw new S3Exception(ioe);
                }

            }
        } catch (SdkClientException e) {
            throw new S3Exception(e);
        }

        return race;
    }
}
