package com.leaguesai.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses OSRS Wiki task tables from HTML using Jsoup.
 */
public class HtmlParser {

    // Matches image file names like Trailblazer_Reloaded_League_tasks_-_Easy.png
    private static final Pattern TIER_IMG_PATTERN =
            Pattern.compile("tasks[_\\- ]+(Easy|Medium|Hard|Elite|Master)", Pattern.CASE_INSENSITIVE);

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

    /**
     * Richer parser for Trailblazer-style task tables. Extracts row attributes
     * ({@code data-taskid}, {@code data-tbz-area-for-filtering}) and image metadata
     * (tier icon in the points column) that the text-only parser loses.
     *
     * <p>Returns a list of {@link TaskRow} records with all structured fields.
     */
    public static List<TaskRow> parseTaskTableRich(String html) {
        List<TaskRow> out = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        // Only the Trailblazer-style task table has the rich data attributes.
        Elements tables = doc.select("table.tbrl-tasks");
        if (tables.isEmpty()) {
            // Fallback: if no tbrl-tasks class, try all wikitables (older formats)
            tables = doc.select("table.wikitable");
        }

        for (Element table : tables) {
            Elements dataRows = table.select("tr:has(td)");
            for (Element row : dataRows) {
                Elements cells = row.select("td");
                if (cells.size() < 3) continue;

                TaskRow tr = new TaskRow();
                tr.taskId = row.attr("data-taskid");
                tr.area = row.attr("data-tbz-area-for-filtering");

                // Column 1: Name (e.g., "1 Easy Clue Scroll")
                if (cells.size() > 1) {
                    tr.name = cells.get(1).text().trim();
                }
                // Column 2: Task description
                if (cells.size() > 2) {
                    tr.description = cells.get(2).text().trim();
                }
                // Column 3: Requirements
                if (cells.size() > 3) {
                    tr.requirements = cells.get(3).text().trim();
                }
                // Column 4: Points (with tier image) — extract both
                if (cells.size() > 4) {
                    Element pointsCell = cells.get(4);
                    tr.points = parsePoints(pointsCell.text());
                    // Look for tier image: <img src="...tasks_-_Easy.png">
                    Element img = pointsCell.selectFirst("img");
                    if (img != null) {
                        String src = img.attr("src");
                        Matcher m = TIER_IMG_PATTERN.matcher(src);
                        if (m.find()) {
                            tr.difficulty = m.group(1).toLowerCase();
                        }
                    }
                }

                if (tr.name == null || tr.name.isEmpty()) continue;
                if (tr.difficulty == null) tr.difficulty = "easy";
                if (tr.area == null || tr.area.isEmpty()) tr.area = "general";

                out.add(tr);
            }
        }
        return out;
    }

    private static int parsePoints(String raw) {
        if (raw == null) return 0;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Structured task row with metadata the text-only parser drops. */
    public static class TaskRow {
        public String taskId;
        public String name;
        public String description;
        public String requirements;
        public String area;
        public String difficulty;
        public int points;
    }
}
