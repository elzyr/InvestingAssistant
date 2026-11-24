package pl.investassist.expense.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfTextExtractor {

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
    try (PDDocument document = Loader.loadPDF(pdfBytes)) {
      if (document.getNumberOfPages() == 0) {
        return "";
      }
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      String text = stripper.getText(document);
      return text != null ? text : "";
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("error.expense_invalid_pdf", e);
    }
  }
}
