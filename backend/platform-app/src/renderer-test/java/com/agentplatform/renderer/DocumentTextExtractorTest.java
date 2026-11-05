package com.agentplatform.renderer;

import java.nio.file.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real parser evidence; container isolation and resource evidence remain separate gates. */
class DocumentTextExtractorTest {
    @TempDir Path root;
    private String text(Path source,String format)throws Exception {
        Path target=root.resolve(source.getFileName()+".txt");DocumentTextExtractor.extract(source,target,format);return Files.readString(target);
    }
    @Test void docxExtractsUnicodeParagraphsWithoutOfficeConversion()throws Exception {
        Path source=root.resolve("input.docx");
        try(var document=new XWPFDocument();var stream=Files.newOutputStream(source)){
            document.createParagraph().createRun().setText("文件正文 Chinese 666");document.write(stream);
        }
        assertTrue(text(source,"docx").contains("文件正文 Chinese 666"));
    }
    @Test void xlsxIncludesCellsOutsideThePrintArea()throws Exception {
        Path source=root.resolve("input.xlsx");
        try(var workbook=new XSSFWorkbook();var stream=Files.newOutputStream(source)){
            var sheet=workbook.createSheet("数据");sheet.createRow(0).createCell(0).setCellValue("visible");
            sheet.createRow(150).createCell(8).setCellValue("OUTSIDE_PRINT_AREA_666");workbook.setPrintArea(0,0,1,0,2);workbook.write(stream);
        }
        assertTrue(text(source,"xlsx").contains("OUTSIDE_PRINT_AREA_666"));
    }
    @Test void pptxExtractsSlideText()throws Exception {
        Path source=root.resolve("input.pptx");
        try(var slides=new XMLSlideShow();var stream=Files.newOutputStream(source)){
            slides.createSlide().createTextBox().setText("幻灯片正文 666");slides.write(stream);
        }
        assertTrue(text(source,"pptx").contains("幻灯片正文 666"));
    }
    @Test void invalidUtf8EmptyAndBinaryNeverPublishSuccessfulText()throws Exception {
        Path source=root.resolve("source");
        Files.write(source,new byte[]{(byte)0xc3,0x28});
        assertEquals("EXTRACTION_TEXT_ENCODING_UNSUPPORTED",assertThrows(PreviewRendererMain.Rejected.class,()->text(source,"txt")).code);
        Files.writeString(source," \n\t");
        assertEquals("EXTRACTION_NO_TEXT",assertThrows(PreviewRendererMain.Rejected.class,()->text(source,"txt")).code);
        Files.writeString(source,"hello\0binary");
        assertEquals("EXTRACTION_TEXT_BINARY_REJECTED",assertThrows(PreviewRendererMain.Rejected.class,()->text(source,"txt")).code);
    }
    @Test void outputLimitRejectsRatherThanPublishesTruncatedText()throws Exception {
        Path source=root.resolve("big.txt");
        try(var writer=Files.newBufferedWriter(source)){String chunk="中".repeat(8192);for(int i=0;i<86;i++)writer.write(chunk);}
        assertEquals("EXTRACTION_TEXT_TOO_LARGE",assertThrows(PreviewRendererMain.Rejected.class,()->text(source,"txt")).code);
        assertFalse(Files.exists(root.resolve("big.txt.txt")));
    }
}
