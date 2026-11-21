"""Historical one-page batch generator; superseded by generate-demo-contracts.py.

Kept for reproducing the original journal, not for creating new catalog documents.
"""
import argparse
from datetime import date
import hashlib
import json
from pathlib import Path
import re
from xml.sax.saxutils import escape
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate,Paragraph,Spacer,Table,TableStyle
from pypdf import PdfReader,PdfWriter

def run(source,output):
    snapshot=json.loads(source.read_text(encoding='utf-8'))
    output.mkdir(parents=True,exist_ok=True);docs=output/'documents';docs.mkdir(exist_ok=True)
    pdfmetrics.registerFont(TTFont('Arial','C:/Windows/Fonts/arial.ttf'))
    pdfmetrics.registerFont(TTFont('ArialBold','C:/Windows/Fonts/arialbd.ttf'))
    styles={name:ParagraphStyle(name,fontName=font,fontSize=size,leading=leading,textColor=colors.HexColor('#'+color),spaceAfter=gap)
        for name,font,size,leading,color,gap in [
            ('title','ArialBold',19,25,'202538',14),('label','ArialBold',10,15,'4b456b',5),
            ('body','Arial',10,16,'303342',10),('small','Arial',8,12,'5b6070',7),
            ('warning','ArialBold',12,18,'8e293c',12)]}
    issue='2026-11-06';expiry='2027-11-06';batch='demo-rights-20261106'
    rows=[]
    for track in sorted(snapshot['tracks'],key=lambda t:str(t['_id'])):
        key=track['_id'].get('$oid') if isinstance(track['_id'],dict) else str(track['_id'])
        assert re.fullmatch('[A-Za-z0-9_-]{1,64}',key)
        code='DEMO-20261106-'+key
        title=str(track.get('name') or key);artist=str(track.get('artist_name') or 'Chưa xác định')
        pdf=docs/(key+'.pdf');assert not pdf.exists(),'Immutable document already exists'
        def P(text,style='body'):return Paragraph(escape(text),styles[style])
        story=[P('MOODIFY · HỒ SƠ MÔ PHỎNG','label'),P('PHIẾU CẤP PHÉP DEMO','title'),
            P('DEMO — KHÔNG CÓ GIÁ TRỊ CẤP QUYỀN THỰC TẾ','warning'),
            P('Tài liệu do hệ thống tạo để trình diễn liên kết hồ sơ, kiểm duyệt và đề xuất nhạc. Không xác nhận quyền tác giả, quyền bản ghi hoặc sự chấp thuận của nghệ sĩ/đơn vị phân phối.'),
            P('1. Định danh hồ sơ','label'),P('Mã hồ sơ: '+code),P('Mã bản ghi Moodify: '+key),
            P('Tên bài: '+title),P('Nghệ sĩ theo metadata bản ghi: '+artist),
            P('2. Các bên và phạm vi mô phỏng','label'),
            P('Bên cấp phép giả định: CHỦ THỂ DEMO. Bên nhận giả định: MOODIFY DEMO. Không sử dụng tên bên thật để thể hiện rằng họ đã ký tài liệu này.'),
            P('Loại hồ sơ dữ liệu: DEMO_SIMULATED. Phạm vi: mô phỏng luồng cấp phép → kiểm duyệt → tạo gợi ý → phát nhạc trong bản trình diễn hệ thống.'),
            P('3. Thời hạn và chi phí mô phỏng','label'),
            P('Ngày bắt đầu mô phỏng: '+issue+' · Ngày kết thúc mô phỏng: '+expiry+'.'),
            P('Chi phí mô phỏng: 0 VNĐ. Tài liệu không phát sinh thanh toán hoặc doanh thu thật. Trạng thái ACTIVE chỉ mô tả hiệu lực của dữ liệu demo.'),
            P('4. Xác nhận nguồn và tính toàn vẹn','label'),
            P('Batch: '+batch+' · Chứng từ gắn với đúng mã bản ghi nêu trên. Audio, lời bài hát và metadata âm nhạc được giữ nguyên.'),
            P('Hash audio tại thời điểm lập: '+str(track.get('audio_hash') or 'Chưa có hash trong dữ liệu nguồn'),'small'),
            P('Không có chữ ký, con dấu hoặc chữ ký số thật. Chuyển sang vận hành quyền thật cần hồ sơ và kiểm duyệt riêng; không tái sử dụng phiếu demo làm bằng chứng cấp quyền.','small')]
        def page(canvas,doc):
            canvas.saveState();canvas.setFont('ArialBold',55);canvas.setFillColor(colors.HexColor('#eeeeF5'))
            canvas.translate(300,400);canvas.rotate(35);canvas.drawCentredString(0,0,'DEMO');canvas.restoreState()
            canvas.setFont('Arial',8);canvas.setFillColor(colors.HexColor('#626778'))
            canvas.drawString(42,25,'MOODIFY DEMO · '+key);canvas.drawRightString(A4[0]-42,25,str(doc.page))
        SimpleDocTemplate(str(pdf),pagesize=A4,leftMargin=42,rightMargin=42,topMargin=35,bottomMargin=40,
            title='DEMO - '+title,author='Moodify Demo Generator').build(story,onFirstPage=page,onLaterPages=page)
        reader=PdfReader(pdf);text='\n'.join(p.extract_text() or '' for p in reader.pages)
        assert key in text and 'DEMO_SIMULATED' in text and 'KHÔNG CÓ GIÁ TRỊ' in text
        rows.append({'track_id':key,'track_name':title,'artist_name':artist,'contract_code':code,
            'license_type':'DEMO_SIMULATED','status':'ACTIVE','issue_date':issue,'expiry_date':expiry,
            'copyright_owner':'DEMO - simulated rights holder','acquisition_cost_vnd':0,
            'document_url':'/uploads/licenses/demo-20261106/'+key+'.pdf','pdf_sha256':hashlib.sha256(pdf.read_bytes()).hexdigest(),
            'pages':len(reader.pages),'simulated':True})
    manifest={'batch_id':batch,'simulated':True,'not_real_rights':True,'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'rows':rows}
    (output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    combined=PdfWriter()
    for row in rows:combined.append(docs/(row['track_id']+'.pdf'))
    with (output/'demo-review-bundle.pdf').open('wb') as stream:combined.write(stream)
    print(json.dumps({'documents':len(rows),'pages':sum(r['pages'] for r in rows),'max_pages':max(r['pages'] for r in rows),'simulated':True}))

if __name__=='__main__':
    raise SystemExit('Superseded: use generate-demo-contracts.py with the three-page template and live source/previous manifest.')
