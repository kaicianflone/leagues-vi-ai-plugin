package com.leaguesai.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses OSRS Wiki task tables from HTML using Jsoup.
 */
public class HtmlParser {

    /**
     * Parses all {@code <table class="wikitable">} elements in the given HTML and
     * returns each data row as a map of header → cell value.
     *
     * <p>Multiple tables are concatenated into a single list. Tables that contain
     * only a header row (i.e. no data rows) contribute nothing to the result.
     *
     * @param html raw HTML string
     * @return list of row maps; empty list if no data rows are found
     */
    public static List<Map<String, String>> parseTaskTable(String html) {
        List<Map<String, String>> rows = new ArrayList<>();

        Document doc = Jsoup.parse(html);
        Elements tables = doc.select("table.wikitable");

        for (Element table : tables) {
            // Collect headers from the first <tr> that contains <th> elements
            List<String> headers = new ArrayList<>();
            Element headerRow = table.selectFirst("tr");
            if (headerRow != null) {
                for (Element th : headerRow.select("th")) {
                    headers.add(th.text().trim());
                }
            }

            if (headers.isEmpty()) {
                continue;
            }

            // Collect data rows — every <tr> that contains <td> elements
            Elements dataRows = table.select("tr:has(td)");
            for (Element row : dataRows) {
                Elements cells = row.select("td");
                Map<String, String> rowMap = new LinkedHashMap<>();
                for (int i = 0; i < headers.size() && i < cells.size(); i++) {
                    rowMap.put(headers.get(i), cells.get(i).text().trim());
                }
                rows.add(rowMap);
            }
        }

        return rows;
    }
}
