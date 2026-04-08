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

    // =========================================================================
    // Demonic Pacts League parsers (phase 1 — relics / areas / pacts)
    // =========================================================================

    /** Pulls the numeric tier out of a header id like {@code "Tier_1"}. */
    private static final Pattern TIER_ID_PATTERN =
            Pattern.compile("Tier[_\\s]*(\\d+)", Pattern.CASE_INSENSITIVE);

    /** Pulls the pact code out of an image filename like {@code Pact_AA_(...)}. */
    private static final Pattern PACT_CODE_PATTERN =
            Pattern.compile("Pact[_\\s]*([A-Za-z0-9]+)");

    /**
     * Parse the Demonic Pacts League Relics page.
     *
     * <p>Expected structure (verified against the live wiki 2026-04-08):
     * a series of {@code <h3 id="Tier_N">} headers each followed by a
     * {@code <table class="wikitable lighttable">} with columns
     * Icon / Name / Effect. Tier is taken from the preceding header id.
     *
     * <p>Effect is the plain-text rendering of the third cell (bullet lists
     * flattened to " / " separators). Unlock cost is not on the page, so
     * it is returned as 0 — callers should treat it as "unknown".
     */
    public static List<RelicRow> parseRelicsPage(String html) {
        List<RelicRow> out = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        // Walk every h3 whose id starts with "Tier_". For each, find the
        // nearest following .wikitable.lighttable and parse its rows.
        Elements headers = doc.select("h3[id^=Tier_]");
        for (Element header : headers) {
            Matcher m = TIER_ID_PATTERN.matcher(header.attr("id"));
            if (!m.find()) continue;
            int tier;
            try {
                tier = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }

            // MediaWiki wraps h3 in a div.mw-heading; the table lives after
            // that wrapper. Walk forward across siblings until we hit a
            // wikitable.lighttable or another Tier_ header.
            Element cursor = header.parent();
            if (cursor == null) cursor = header;
            Element table = null;
            Element sib = cursor.nextElementSibling();
            while (sib != null) {
                if (sib.tagName().equals("table")
                        && sib.hasClass("wikitable")
                        && sib.hasClass("lighttable")) {
                    table = sib;
                    break;
                }
                // Stop at the next Tier_ heading wrapper to avoid spilling into
                // the following tier's table.
                if (sib.selectFirst("h3[id^=Tier_]") != null) break;
                sib = sib.nextElementSibling();
            }
            if (table == null) continue;

            for (Element row : table.select("tr:has(td)")) {
                Elements cells = row.select("td");
                if (cells.size() < 3) continue;

                RelicRow rr = new RelicRow();
                rr.tier = tier;

                Element nameCell = cells.get(1);
                Element nameLink = nameCell.selectFirst("a");
                rr.name = nameLink != null ? nameLink.text().trim() : nameCell.text().trim();

                // Flatten bullet list into a single string so downstream can
                // store it in a single TEXT column without losing meaning.
                Element effectCell = cells.get(2);
                rr.effect = flattenBullets(effectCell);

                if (rr.name.isEmpty()) continue;
                out.add(rr);
            }
        }
        return out;
    }

    /**
     * Parse the Demonic Pacts League Areas page. Returns one row per
     * top-level {@code <h2>} area heading, skipping {@code General_information}
     * and {@code Misthalin} (not accessible in Leagues VI).
     *
     * <p>Phase 1 note: the page does not yet list unlock point costs per
     * area. Costs are returned as 0 and will be backfilled in phase 2.
     */
    public static List<AreaRow> parseAreasPage(String html) {
        List<AreaRow> out = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        for (Element h2 : doc.select("h2[id]")) {
            String id = h2.attr("id");
            if (id.isEmpty()) continue;
            if (id.equals("General_information")) continue;
            if (id.equals("mw-toc-heading")) continue;
            if (id.equals("Misthalin")) continue; // not accessible in Leagues VI

            AreaRow ar = new AreaRow();
            ar.id = id;
            ar.name = h2.text().trim();
            ar.unlockCost = 0; // TODO phase 2: parse from tasks page once numbers land
            if (!ar.name.isEmpty()) {
                out.add(ar);
            }
        }
        return out;
    }

    /**
     * Parse the Demonic Pacts League Demonic Pacts page.
     *
     * <p>The wiki ships a single {@code <table class="wikitable sortable lighttable">}
     * with two columns: Icon | Effect. The pact "name" is a code embedded in
     * the icon image filename (e.g. {@code Pact_AA_(Demonic_Pacts_League).png}
     * → code {@code AA}). The wiki does not yet document node type
     * (Minor/Major/Master) or an unlock tree, so both are left null.
     */
    public static List<PactRow> parsePactsPage(String html) {
        List<PactRow> out = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        // Limit the search to tables inside the "Skill tree" section — the
        // sidebar and navbox also carry .wikitable.lighttable classes in the
        // wider MediaWiki theme.
        Elements tables = doc.select("table.wikitable.lighttable");
        for (Element table : tables) {
            // Require a header row of exactly Icon + Effect to avoid matching
            // the relic tables if a parser is ever handed a combined page.
            Elements ths = table.select("tr:first-child th");
            if (ths.size() != 2) continue;
            String h1 = ths.get(0).text().trim().toLowerCase();
            String h2 = ths.get(1).text().trim().toLowerCase();
            if (!h1.equals("icon") || !h2.equals("effect")) continue;

            for (Element row : table.select("tr:has(td)")) {
                Elements cells = row.select("td");
                if (cells.size() < 2) continue;

                Element img = cells.get(0).selectFirst("img");
                if (img == null) continue;
                String src = img.attr("src");
                Matcher m = PACT_CODE_PATTERN.matcher(src);
                if (!m.find()) continue;
                String code = m.group(1);

                PactRow pr = new PactRow();
                pr.code = code;
                pr.effect = flattenBullets(cells.get(1));
                if (!pr.effect.isEmpty()) {
                    out.add(pr);
                }
            }
        }
        return out;
    }

    /**
     * Flatten a cell that may contain {@code <ul><li>} or {@code <p>} blocks
     * into a single string. Bullet items are joined with " / " so the
     * original phrasing survives while fitting in a single TEXT column.
     */
    private static String flattenBullets(Element cell) {
        Elements items = cell.select("li");
        if (!items.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(" / ");
                sb.append(items.get(i).ownText().trim());
            }
            // Also append any trailing <p> text that isn't in a list, so
            // footnote paragraphs under pact rows survive.
            for (Element p : cell.select("> p, p")) {
                String pt = p.text().trim();
                if (!pt.isEmpty()) sb.append(" / ").append(pt);
            }
            return sb.toString().trim();
        }
        return cell.text().trim();
    }

    /** Relic row result — tier from preceding h3, name from name cell, effect flattened. */
    public static class RelicRow {
        public int tier;
        public String name;
        public String effect;
    }

    /** Area row result — id from h2 anchor id, name from h2 text. */
    public static class AreaRow {
        public String id;
        public String name;
        public int unlockCost; // 0 in phase 1 — wiki does not list
    }

    /** Pact row result — code from image filename, effect from effect cell. */
    public static class PactRow {
        public String code;
        public String effect;
    }
}
