package com.rkghrud.shipapp.data;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

public final class TrackingSpreadsheetImporter {
    private static final List<String> MATCH_HEADER_ALIASES = Arrays.asList(
            "shipment", "shipmentid", "shipmentno", "shipment_box_id", "shipmentboxid", "shipmentbox",
            "orderitemcode", "orderitemid", "order_item_code", "vendoritemid", "vendor_item_id",
            "orderid", "order_id", "주문번호", "주문상품번호", "주문상품코드", "상품주문번호"
    );

    private static final List<String> TRACKING_HEADER_ALIASES = Arrays.asList(
            "tracking", "trackingno", "trackingnumber", "tracking_no",
            "invoice", "invoiceno", "invoice_number", "송장번호", "운송장번호", "운송장", "송장"
    );

    private TrackingSpreadsheetImporter() {
    }

    public static MatchResult applyToOrders(Context context, Uri uri, List<DispatchOrder> orders) throws Exception {
        String displayName = resolveDisplayName(context, uri);
        List<List<String>> rows = readRows(context, uri, displayName);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("스프레드시트에서 읽은 데이터가 없습니다.");
        }

        HeaderSelection headers = findHeaderSelection(rows);
        if (headers == null) {
            throw new IllegalArgumentException("shipment / 주문번호 / 송장번호 헤더를 찾지 못했습니다.");
        }

        Map<String, String> trackingByKey = new LinkedHashMap<>();
        int trackingRowCount = 0;

        for (int rowIndex = headers.headerRowIndex + 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            String trackingNumber = normalizeTracking(getCell(row, headers.trackingIndex));
            if (trackingNumber.isEmpty()) {
                continue;
            }

            List<String> rowKeys = new ArrayList<>();
            for (Integer keyIndex : headers.keyIndexes) {
                String normalized = normalizeKey(getCell(row, keyIndex));
                if (!normalized.isEmpty() && !rowKeys.contains(normalized)) {
                    rowKeys.add(normalized);
                }
            }

            if (rowKeys.isEmpty()) {
                continue;
            }

            trackingRowCount++;
            for (String key : rowKeys) {
                if (!trackingByKey.containsKey(key)) {
                    trackingByKey.put(key, trackingNumber);
                }
            }
        }

        int matchedCount = 0;
        for (DispatchOrder order : orders) {
            String matchedTracking = findTrackingForOrder(order, trackingByKey);
            if (matchedTracking == null) {
                continue;
            }
            order.trackingNumber = matchedTracking;
            order.selected = true;
            matchedCount++;
        }

        return new MatchResult(displayName, rows.size(), trackingRowCount, matchedCount);
    }

    private static String findTrackingForOrder(DispatchOrder order, Map<String, String> trackingByKey) {
        for (String key : order.getMatchKeys()) {
            String matched = trackingByKey.get(normalizeKey(key));
            if (matched != null && !matched.isEmpty()) {
                return matched;
            }
        }
        return null;
    }

    private static List<List<String>> readRows(Context context, Uri uri, String displayName) throws Exception {
        String lowerName = displayName.toLowerCase(Locale.ROOT);
        String mimeType = context.getContentResolver().getType(uri);
        boolean isXlsx = lowerName.endsWith(".xlsx")
                || (mimeType != null && mimeType.contains("spreadsheetml"));

        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("파일을 열지 못했습니다.");
            }

            if (isXlsx) {
                return parseXlsx(inputStream);
            }

            String text = new String(readAllBytes(inputStream), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
                text = text.substring(1);
            }
            char delimiter = detectDelimiter(text);
            return parseDelimited(text, delimiter);
        }
    }

    private static List<List<String>> parseXlsx(InputStream inputStream) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(entry.getName(), readAllBytes(zipInputStream));
            }
        }

        List<String> worksheetPaths = new ArrayList<>();
        for (String name : entries.keySet()) {
            if (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) {
                worksheetPaths.add(name);
            }
        }
        Collections.sort(worksheetPaths);
        if (worksheetPaths.isEmpty()) {
            throw new IllegalArgumentException("엑셀 시트를 찾지 못했습니다.");
        }

        List<String> sharedStrings = parseSharedStrings(entries.get("xl/sharedStrings.xml"));
        return parseSheet(entries.get(worksheetPaths.get(0)), sharedStrings);
    }

    private static List<String> parseSharedStrings(byte[] bytes) throws Exception {
        List<String> values = new ArrayList<>();
        if (bytes == null || bytes.length == 0) {
            return values;
        }

        Document document = parseXml(bytes);
        NodeList nodes = document.getElementsByTagName("si");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            values.add(cleanValue(element.getTextContent()));
        }
        return values;
    }

    private static List<List<String>> parseSheet(byte[] bytes, List<String> sharedStrings) throws Exception {
        Document document = parseXml(bytes);
        NodeList rowNodes = document.getElementsByTagName("row");
        List<List<String>> rows = new ArrayList<>();

        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element rowElement = (Element) rowNodes.item(i);
            NodeList cellNodes = rowElement.getElementsByTagName("c");
            List<String> row = new ArrayList<>();

            for (int j = 0; j < cellNodes.getLength(); j++) {
                Element cellElement = (Element) cellNodes.item(j);
                int columnIndex = columnIndexFromReference(cellElement.getAttribute("r"));
                while (row.size() < columnIndex) {
                    row.add("");
                }
                row.add(readCellValue(cellElement, sharedStrings));
            }

            trimTrailingEmptyCells(row);
            rows.add(row);
        }

        return rows;
    }

    private static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static String readCellValue(Element cellElement, List<String> sharedStrings) {
        String type = cellElement.getAttribute("t");
        if ("inlineStr".equals(type)) {
            NodeList textNodes = cellElement.getElementsByTagName("t");
            return textNodes.getLength() > 0 ? cleanValue(textNodes.item(0).getTextContent()) : "";
        }

        NodeList valueNodes = cellElement.getElementsByTagName("v");
        if (valueNodes.getLength() == 0) {
            return "";
        }

        String raw = cleanValue(valueNodes.item(0).getTextContent());
        if ("s".equals(type)) {
            try {
                int sharedIndex = Integer.parseInt(raw);
                return sharedIndex >= 0 && sharedIndex < sharedStrings.size() ? sharedStrings.get(sharedIndex) : "";
            } catch (Exception ignored) {
                return "";
            }
        }
        return raw;
    }

    private static int columnIndexFromReference(String reference) {
        if (reference == null || reference.isEmpty()) {
            return 0;
        }

        int result = 0;
        for (int i = 0; i < reference.length(); i++) {
            char ch = reference.charAt(i);
            if (!Character.isLetter(ch)) {
                break;
            }
            result = result * 26 + (Character.toUpperCase(ch) - 'A' + 1);
        }
        return Math.max(0, result - 1);
    }

    private static void trimTrailingEmptyCells(List<String> row) {
        for (int i = row.size() - 1; i >= 0; i--) {
            if (!row.get(i).isEmpty()) {
                break;
            }
            row.remove(i);
        }
    }

    private static HeaderSelection findHeaderSelection(List<List<String>> rows) {
        int limit = Math.min(rows.size(), 6);
        for (int i = 0; i < limit; i++) {
            List<String> row = rows.get(i);
            HeaderSelection selection = parseHeaderRow(row, i);
            if (selection != null) {
                return selection;
            }
        }
        return null;
    }

    private static HeaderSelection parseHeaderRow(List<String> row, int headerRowIndex) {
        List<Integer> keyIndexes = new ArrayList<>();
        int trackingIndex = -1;

        for (int i = 0; i < row.size(); i++) {
            String normalizedHeader = normalizeHeader(row.get(i));
            if (normalizedHeader.isEmpty()) {
                continue;
            }
            if (trackingIndex < 0 && TRACKING_HEADER_ALIASES.contains(normalizedHeader)) {
                trackingIndex = i;
            }
            if (MATCH_HEADER_ALIASES.contains(normalizedHeader)) {
                keyIndexes.add(i);
            }
        }

        if (trackingIndex < 0 || keyIndexes.isEmpty()) {
            return null;
        }
        return new HeaderSelection(headerRowIndex, keyIndexes, trackingIndex);
    }

    private static List<List<String>> parseDelimited(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        value.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    value.append(ch);
                }
                continue;
            }

            if (ch == '"') {
                inQuotes = true;
                continue;
            }

            if (ch == delimiter) {
                row.add(cleanValue(value.toString()));
                value.setLength(0);
                continue;
            }

            if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cleanValue(value.toString()));
                value.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                continue;
            }

            value.append(ch);
        }

        if (value.length() > 0 || !row.isEmpty()) {
            row.add(cleanValue(value.toString()));
            rows.add(row);
        }

        return rows;
    }

    private static char detectDelimiter(String text) {
        String[] lines = text.split("\\r?\\n", 6);
        int commaScore = 0;
        int tabScore = 0;
        for (String line : lines) {
            if (line.indexOf(',') >= 0) {
                commaScore++;
            }
            if (line.indexOf('\t') >= 0) {
                tabScore++;
            }
        }
        return tabScore > commaScore ? '\t' : ',';
    }

    private static String resolveDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "tracking-import";
    }

    private static byte[] readAllBytes(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private static String getCell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : "";
    }

    private static String normalizeHeader(String value) {
        return cleanValue(value)
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "")
                .replace("/", "")
                .replace("(", "")
                .replace(")", "")
                .replace(".", "");
    }

    private static String normalizeKey(String value) {
        return cleanValue(value).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static String normalizeTracking(String value) {
        return cleanValue(value).replaceAll("[^A-Za-z0-9]", "");
    }

    private static String cleanValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class HeaderSelection {
        final int headerRowIndex;
        final List<Integer> keyIndexes;
        final int trackingIndex;

        HeaderSelection(int headerRowIndex, List<Integer> keyIndexes, int trackingIndex) {
            this.headerRowIndex = headerRowIndex;
            this.keyIndexes = keyIndexes;
            this.trackingIndex = trackingIndex;
        }
    }

    public static final class MatchResult {
        public final String fileName;
        public final int rowCount;
        public final int trackingRowCount;
        public final int matchedCount;

        MatchResult(String fileName, int rowCount, int trackingRowCount, int matchedCount) {
            this.fileName = fileName;
            this.rowCount = rowCount;
            this.trackingRowCount = trackingRowCount;
            this.matchedCount = matchedCount;
        }

        public String summary() {
            return fileName + " · 송장행 " + trackingRowCount + "개, 주문 매칭 " + matchedCount + "건";
        }
    }
}
