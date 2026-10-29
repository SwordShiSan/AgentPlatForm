"""Generate small deterministic preview input samples; no business data or supplier calls."""
from pathlib import Path
import argparse, hashlib, json


def generate(destination: Path):
    # These mature fixture libraries are test-only and never enter the application image.
    from docx import Document
    from pptx import Presentation
    from pptx.util import Inches
    import xlsxwriter
    from PIL import Image
    destination.mkdir(parents=True, exist_ok=True)
    document = Document()
    document.add_heading('R5.3 preview / 中文预览', 0)
    document.add_paragraph('Immutable source version. 666. 中文字体与普通 Office 文档。')
    document.add_table(rows=2, cols=2).cell(0, 0).text = '固定版本'
    document.save(destination / 'sample.docx')
    sheetbook = xlsxwriter.Workbook(str(destination / 'sample.xlsx'))
    sheet = sheetbook.add_worksheet('预览')
    sheet.write_row(0, 0, ['名称', '数值'])
    sheet.write_row(1, 0, ['R53', 666])
    sheet.set_column(0, 0, 28)
    sheet.set_landscape()
    sheet.fit_to_pages(1, 1)
    sheetbook.close()
    presentation = Presentation()
    slide = presentation.slides.add_slide(presentation.slide_layouts[6])
    slide.shapes.add_textbox(Inches(1), Inches(1), Inches(8), Inches(2)).text_frame.text = 'R5.3 中文预览 666'
    presentation.save(destination / 'sample.pptx')
    Image.new('RGB', (64, 48), '#3868ad').save(destination / 'sample.png')
    Image.new('RGB', (64, 48), '#ad6838').save(destination / 'sample.webp')
    Image.new('RGB', (64, 48), '#38ad68').save(destination / 'sample.gif')
    (destination / 'empty.txt').write_bytes(b'')
    (destination / 'sample.txt').write_text('中文 / emoji 🙂 / 666\r\n', encoding='utf-8')
    (destination / 'sample.html').write_text('<h1>中文预览</h1><p>666</p><script>window.previewExecuted=true</script><img src="https://example.invalid/no-fetch">', encoding='utf-8')
    (destination / 'corrupt.docx').write_bytes(b'Not an Office package')
    manifest = {}
    for path in destination.iterdir():
        if path.name == 'fixture-manifest.json' or not path.is_file():
            continue
        manifest[path.name] = {'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    (destination / 'fixture-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'files': len(manifest), 'directory': str(destination)}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('.cache/r53-local/preview-fixtures'))
    generate(parser.parse_args().output)
