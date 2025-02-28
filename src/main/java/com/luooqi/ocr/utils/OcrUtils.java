package com.luooqi.ocr.utils;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.log.StaticLog;
import com.luooqi.ocr.model.TextBlock;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * tools-ocr
 * Created by 何志龙 on 2019-03-22.
 */
public class OcrUtils {

    public static String ocrImg(byte[] imgData) {
        int i = Math.abs(UUID.randomUUID().hashCode()) % 4;
        StaticLog.info("OCR Engine: " + i);
        switch (i){
            case 0:
                return bdGeneralOcr(imgData);
            case 1:
                return bdAccurateOcr(imgData);
            case 2:
                return sogouMobileOcr(imgData);
            default:
                return sogouWebOcr(imgData);
        }
    }

    private static String bdGeneralOcr(byte[] imgData){
        return bdBaseOcr(imgData, "general_location");
    }

    private static String bdAccurateOcr(byte[] imgData){
        return bdBaseOcr(imgData, "https://aip.baidubce.com/rest/2.0/ocr/v1/accurate");
    }

    private static String bdBaseOcr(byte[] imgData, String type){
        String[] urlArr = new String[]{"http://ai.baidu.com/tech/ocr/general", "http://ai.baidu.com/index/seccode?action=show"};
        StringBuilder cookie = new StringBuilder();
        for (String url : urlArr) {
            HttpResponse cookieResp = WebUtils.get(url);
            List<String> ckList = cookieResp.headerList("Set-Cookie");
            for (String s : ckList) {
                cookie.append(s.replaceAll("expires[\\S\\s]+", ""));
            }
        }
        HashMap<String, String> header = new HashMap<>();
        header.put("Referer", "http://ai.baidu.com/tech/ocr/general");
        header.put("Cookie", cookie.toString());
        String data = "type="+URLUtil.encodeQuery(type)+"&detect_direction=false&image_url&image=" + URLUtil.encodeQuery("data:image/jpeg;base64," + Base64.encode(imgData)) + "&language_type=CHN_ENG";
        HttpResponse response = WebUtils.postRaw("http://ai.baidu.com/aidemo", data, 0, header);
        return extractBdResult(WebUtils.getSafeHtml(response));
    }

    public static String sogouMobileOcr(byte[] imgData) {
        String boundary = "------WebKitFormBoundary8orYTmcj8BHvQpVU";
        String url = "http://ocr.shouji.sogou.com/v2/ocr/json";
        String header = boundary + "\r\nContent-Disposition: form-data; name=\"pic\"; filename=\"pic.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n";
        String footer = "\r\n" + boundary + "--\r\n";
        byte[] postData = CommUtils.mergeByte(header.getBytes(CharsetUtil.CHARSET_ISO_8859_1), imgData, footer.getBytes(CharsetUtil.CHARSET_ISO_8859_1));
        return extractSogouResult(CommUtils.postMultiData(url, postData, boundary.substring(2)));
    }

    public static String sogouWebOcr(byte[] imgData) {
        String url = "https://deepi.sogou.com/api/sogouService";
        String referer = "https://deepi.sogou.com/?from=picsearch&tdsourcetag=s_pctim_aiomsg";
        String imageData = Base64.encode(imgData);
        long t = System.currentTimeMillis();
        String sign = SecureUtil.md5("sogou_ocr_just_for_deepibasicOpenOcr" + t + imageData.substring(0, Math.min(1024, imageData.length())) + "4b66a37108dab018ace616c4ae07e644");
        Map<String, Object> data = new HashMap<>();
        data.put("image", imageData);
        data.put("lang", "zh-Chs");
        data.put("pid", "sogou_ocr_just_for_deepi");
        data.put("salt", t);
        data.put("service", "basicOpenOcr");
        data.put("sign", sign);
        HttpRequest request = HttpUtil.createPost(url).timeout(15000);
        request.form(data);
        request.header("Referer", referer);
        HttpResponse response = request.execute();
        return extractSogouResult(WebUtils.getSafeHtml(response));
    }

    private static String extractSogouResult(String html) {
        if (StrUtil.isBlank(html)) {
            return "";
        }
        JSONObject jsonObject = JSONUtil.parseObj(html);
        if (jsonObject.getInt("success", 0) != 1) {
            return "";
        }
        JSONArray jsonArray = jsonObject.getJSONArray("result");
        List<TextBlock> textBlocks = new ArrayList<>();
        boolean isEng;
        for (int i = 0; i < jsonArray.size(); i++) {
            JSONObject jObj = jsonArray.getJSONObject(i);
            TextBlock textBlock = new TextBlock();
            textBlock.setText(jObj.getStr("content").trim());
            //noinspection SuspiciousToArrayCall
            String[] frames = jObj.getJSONArray("frame").toArray(new String[0]);
            textBlock.setTopLeft(CommUtils.frameToPoint(frames[0]));
            textBlock.setTopRight(CommUtils.frameToPoint(frames[1]));
            textBlock.setBottomRight(CommUtils.frameToPoint(frames[2]));
            textBlock.setBottomLeft(CommUtils.frameToPoint(frames[3]));
            textBlocks.add(textBlock);
        }
        isEng = jsonObject.getStr("lang", "zh-Chs").equals("zh-Chs");
        return CommUtils.combineTextBlocks(textBlocks, isEng);
    }

    private static String extractBdResult(String html) {
        if (StrUtil.isBlank(html)) {
            return "";
        }
        JSONObject jsonObject = JSONUtil.parseObj(html);
        if (jsonObject.getInt("errno", 0) != 0) {
            return "";
        }
        System.out.println("===========");
        System.out.println("===========这个是测试分支的记录");
        JSONArray jsonArray = jsonObject.getJSONObject("data").getJSONArray("words_result");
        List<TextBlock> textBlocks = new ArrayList<>();
        boolean isEng = false;
        for (int i = 0; i < jsonArray.size(); i++) {
            JSONObject jObj = jsonArray.getJSONObject(i);
            TextBlock textBlock = new TextBlock();
            textBlock.setText(jObj.getStr("words").trim());
            //noinspection SuspiciousToArrayCall
            JSONObject location = jObj.getJSONObject("location");
            int top = location.getInt("top");
            int left = location.getInt("left");
            int width = location.getInt("width");
            int height = location.getInt("height");
            textBlock.setTopLeft(new Point(top, left));
            textBlock.setTopRight(new Point(top, left + width));
            textBlock.setBottomLeft(new Point(top + height, left));
            textBlock.setBottomRight(new Point(top + height, left + width));
            textBlocks.add(textBlock);
        }
        return CommUtils.combineTextBlocks(textBlocks, isEng);
    }

}
