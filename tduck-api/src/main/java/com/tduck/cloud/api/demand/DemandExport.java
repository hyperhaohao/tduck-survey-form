package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.form.vo.FormFieldVO;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

/** 需求与公示的服务端 Excel 导出，复用列表权限和筛选口径。 */
@Service
@RequiredArgsConstructor
public class DemandExport {
  /** 单次最多导出行数，避免一次请求占用过多内存。 */
  public static final int MAX_ROWS = 10000;

  private final DemandWorkflow workflow;
  private final DemandFormFields formFields;

  /** 导出结果与是否发生截断。 */
  public static class ExportFile {
    public final byte[] bytes;
    public final boolean truncated;

    ExportFile(byte[] bytes, boolean truncated) {
      this.bytes = bytes;
      this.truncated = truncated;
    }
  }

  /** 导出当前筛选条件下的需求列表；单收集单展开题目，跨收集单追加填报内容文本。 */
  public ExportFile requests(
      Long userId,
      String scope,
      String keyword,
      String decision,
      String progress,
      String formKey,
      String handlerId,
      String submitStart,
      String submitEnd) {
    Map<String, Object> data =
        workflow.exportRows(
            userId,
            scope,
            keyword,
            decision,
            progress,
            formKey,
            handlerId,
            submitStart,
            submitEnd,
            MAX_ROWS);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
    boolean truncated = Boolean.TRUE.equals(data.get("truncated"));
    boolean single = formKey != null && !formKey.isEmpty();
    List<FormFieldVO> answerFields = new ArrayList<>();
    if (single) {
      answerFields.addAll(workflow.formFields(formKey));
      answerFields.removeIf(formFields::isDepartmentField);
    }
    List<String> ids = new ArrayList<>();
    for (Map<String, Object> row : rows) ids.add(str(row.get("id")));
    Map<String, Map<String, Object>> answers = workflow.exportAnswers(ids);
    List<String> headerList =
        new ArrayList<>(Arrays.asList("提报时间", "收集单", "填报者", "填报人部门"));
    List<Integer> widthList = new ArrayList<>(Arrays.asList(20, 26, 14, 16));
    if (single) {
      for (FormFieldVO field : answerFields) {
        headerList.add("填报-" + str(field.getLabel()));
        widthList.add(20);
      }
      headerList.add("其他填报内容");
      widthList.add(40);
    } else {
      headerList.add("填报内容");
      widthList.add(60);
    }
    headerList.addAll(
        Arrays.asList("需求编号", "需求标题", "经办人", "采纳结果", "实施进度", "回复时间"));
    widthList.addAll(Arrays.asList(22, 42, 14, 10, 10, 20));
    int[] widths = new int[widthList.size()];
    for (int i = 0; i < widths.length; i++) widths[i] = widthList.get(i);
    Map<String, List<FormFieldVO>> fieldCache = new LinkedHashMap<>();
    return build(
        "需求列表",
        headerList.toArray(new String[0]),
        widths,
        rows,
        row -> {
          List<String> cells =
              new ArrayList<>(
                  Arrays.asList(
                      time(row.get("createTime")),
                      str(row.get("formName")),
                      str(row.get("submitterName"))));
          Map<String, Object> answer = answers.get(str(row.get("id")));
          Map<String, Object> original =
              answer == null ? Collections.emptyMap() : map(answer.get("originalData"));
          cells.add(answer == null ? "" : join(answer.get("department")));
          if (single) {
            for (FormFieldVO field : answerFields)
              cells.add(formFields.displayValue(field, original));
            cells.add(formFields.otherText(answerFields, original));
          } else {
            String key = str(row.get("formKey"));
            List<FormFieldVO> fields = fieldCache.get(key);
            if (fields == null) {
              fields = new ArrayList<>(workflow.formFields(key));
              fields.removeIf(formFields::isDepartmentField);
              fieldCache.put(key, fields);
            }
            cells.add(formFields.contentText(fields, original));
          }
          cells.addAll(
              Arrays.asList(
                  str(row.get("requestNo")),
                  str(row.get("title")),
                  str(row.get("handlerName")),
                  decisionLabel(row.get("decision")),
                  progressLabel(row.get("progress")),
                  time(row.get("replyTime"))));
          return cells.toArray(new String[0]);
        },
        truncated);
  }

  /** 导出当前筛选条件下当前身份可见的已发布公示。 */
  public ExportFile publications(
      Long userId,
      String formKey,
      String keyword,
      String submitStart,
      String submitEnd) {
    List<Map<String, Object>> all = workflow.publications(userId, false);
    String q = keyword == null ? "" : keyword;
    List<Map<String, Object>> matched = new ArrayList<>();
    for (Map<String, Object> row : all) {
      if (formKey != null
          && !formKey.isEmpty()
          && !formKey.equals(str(row.get("formKey")))) continue;
      if (!q.isEmpty()
          && !str(row.get("title")).contains(q)
          && !str(row.get("summary")).contains(q)) continue;
      String day = time(row.get("createTime")).substring(0, Math.min(10, time(row.get("createTime")).length()));
      if (submitStart != null && !submitStart.isEmpty() && day.compareTo(submitStart) < 0) continue;
      if (submitEnd != null && !submitEnd.isEmpty() && day.compareTo(submitEnd) > 0) continue;
      matched.add(row);
    }
    boolean truncated = matched.size() > MAX_ROWS;
    if (truncated) matched = new ArrayList<>(matched.subList(0, MAX_ROWS));
    String[] headers = {
      "需求标题", "需求编号", "收集单", "填报者", "经办人", "采纳结果", "实施进度", "提报时间", "回复时间", "摘要"
    };
    int[] widths = {42, 22, 26, 14, 14, 10, 10, 20, 20, 50};
    return build(
        "需求公示",
        headers,
        widths,
        matched,
        row ->
            new String[] {
              str(row.get("title")),
              str(row.get("requestNo")),
              str(row.get("formName")),
              str(row.get("submitterName")),
              str(row.get("handlerName")),
              decisionLabel(row.get("decision")),
              progressLabel(row.get("progress")),
              time(row.get("createTime")),
              time(row.get("updateTime")),
              str(row.get("summary"))
            },
        truncated);
  }

  private interface RowWriter {
    String[] cells(Map<String, Object> row);
  }

  /** 生成 xlsx；表头加粗并使用固定列宽，避免自动列宽带来的性能问题。 */
  private ExportFile build(
      String sheetName,
      String[] headers,
      int[] widths,
      List<Map<String, Object>> rows,
      RowWriter writer,
      boolean truncated) {
    if (rows == null) rows = Collections.emptyList();
    SXSSFWorkbook workbook = new SXSSFWorkbook(200);
    try {
      Sheet sheet = workbook.createSheet(sheetName);
      CellStyle headerStyle = workbook.createCellStyle();
      Font font = workbook.createFont();
      font.setBold(true);
      headerStyle.setFont(font);
      headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      Row head = sheet.createRow(0);
      for (int i = 0; i < headers.length; i++) {
        Cell cell = head.createCell(i);
        cell.setCellValue(headers[i]);
        cell.setCellStyle(headerStyle);
        sheet.setColumnWidth(i, Math.max(8, widths[i]) * 256);
      }
      int index = 1;
      for (Map<String, Object> row : rows) {
        String[] values = writer.cells(row);
        Row line = sheet.createRow(index++);
        for (int i = 0; i < headers.length; i++)
          line.createCell(i).setCellValue(clip(i < values.length ? values[i] : ""));
      }
      sheet.createFreezePane(0, 1);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return new ExportFile(out.toByteArray(), truncated);
    } catch (Exception e) {
      throw new BaseException("导出文件生成失败，请稍后重试", e);
    } finally {
      workbook.dispose();
    }
  }

  private static String str(Object value) {
    return value == null ? "" : value.toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
  }

  private static String join(Object value) {
    if (value instanceof Collection) {
      List<String> parts = new ArrayList<>();
      for (Object item : (Collection<?>) value) {
        String text = str(item).trim();
        if (!text.isEmpty()) parts.add(text);
      }
      return String.join("、", parts);
    }
    return str(value);
  }

  private static String clip(String value) {
    if (value == null) return "";
    return value.length() <= 32767 ? value : value.substring(0, 32767);
  }

  private static String time(Object value) {
    if (value == null) return "";
    if (value instanceof java.util.Date)
      return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date) value);
    String s = value.toString().replace('T', ' ');
    return s.length() > 19 ? s.substring(0, 19) : s;
  }

  private static String decisionLabel(Object value) {
    String s = str(value);
    return "ACCEPTED".equals(s)
        ? "采纳"
        : "DEFERRED".equals(s) ? "暂缓" : "REJECTED".equals(s) ? "不采纳" : "待评估";
  }

  private static String progressLabel(Object value) {
    String s = str(value);
    return "COMPLETED".equals(s) ? "已完成" : "IN_PROGRESS".equals(s) ? "实施中" : "待实施";
  }
}
