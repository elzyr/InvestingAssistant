package pl.investassist.expense.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfTextExtractor {

  static final int MAX_PAGES = 200;
  static final int MAX_EXTRACTED_CHARS = 5_000_000;
  private static final long MAX_MEMORY_BYTES = 32L * 1024L * 1024L;
  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  public boolean hasPdfMagicHeader(byte[] bytes) {
    if (bytes == null || bytes.length < PDF_MAGIC.length) {
      return false;
    }
    for (int i = 0; i < PDF_MAGIC.length; i++) {
      if (bytes[i] != PDF_MAGIC[i]) {
        return false;
      }
    }
    return true;
  }

  public String extractText(byte[] pdfBytes) {
    if (!hasPdfMagicHeader(pdfBytes)) {
      throw new IllegalArgumentException("error.expense_invalid_pdf");
    }
    MemoryUsageSetting memoryUsage =
        MemoryUsageSetting.setupMixed(MAX_MEMORY_BYTES, MAX_MEMORY_BYTES);
    try (PDDocument document = Loader.loadPDF(pdfBytes, "", null, null, memoryUsage.streamCache)) {
      int pageCount = document.getNumberOfPages();
      if (pageCount == 0) {
        return "";
      }
      if (pageCount > MAX_PAGES) {
        throw new IllegalArgumentException("error.expense_pdf_too_many_pages");
      }
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      StringBuilder out = new StringBuilder();
      for (int page = 1; page <= pageCount; page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String pageText = stripper.getText(document);
        if (pageText != null) {
          if (out.length() + pageText.length() > MAX_EXTRACTED_CHARS) {
            throw new IllegalArgumentException("error.expense_pdf_text_too_large");
          }
          out.append(pageText);
        }
      }
      return out.toString();
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("error.expense_invalid_pdf", e);
    }
  }
}
