"""Render the user's three-page unsigned contract, preserving live demo terms.

The original one-page batch remains immutable. Publication uses a separate CAS job.
"""
import argparse
from datetime import date
import hashlib
import json
import re
from pathlib import Path
from xml.sax.saxutils import escape
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak
from pypdf import PdfReader

OWNER = 'Đơn vị cấp quyền Minh An (giả định)'
VERSION = 'natural-contract-v2'

def track_id(t):
    return t['_id'].get('$oid') if isinstance(t['_id'], dict) else str(t['_id'])

def fonts():
    for name, file in [('TimesVN','times.ttf'),('TimesVN-Bold','timesbd.ttf'),('TimesVN-Italic','timesi.ttf')]:
        pdfmetrics.registerFont(TTFont(name,'C:/Windows/Fonts/'+file))
    pdfmetrics.registerFontFamily('TimesVN',normal='TimesVN',bold='TimesVN-Bold',italic='TimesVN-Italic',boldItalic='TimesVN-Bold')

def render(t, row, target):
    title=str(t['name']); artist=str(t.get('artist_name') or 'Chưa xác định trong danh mục')
    album=str(t.get('album_name') or 'Chưa xác định trong danh mục')
    featured=t.get('featured_artists')
    if isinstance(featured,list):
        featured=', '.join(str(x.get('name') or x.get('artist_name') or '') if isinstance(x,dict) else str(x) for x in featured)
    key=track_id(t); code=row['contract_code']
    start=date.fromisoformat(row['issue_date']); end=date.fromisoformat(row['expiry_date'])
    sl=start.strftime('%d/%m/%Y'); el=end.strftime('%d/%m/%Y')
    assert int(row['acquisition_cost_vnd'])==0, 'Do not change costs or invent amount-in-words'
    W,H=A4; width=W-85.04-56.69
    styles={name:ParagraphStyle(name,fontName=font,fontSize=size,leading=leading,spaceAfter=gap,alignment=align)
        for name,font,size,leading,gap,align in [
            ('body','TimesVN',11.5,14.6,6,TA_JUSTIFY),('title','TimesVN-Bold',16,21,9,TA_CENTER),
            ('center','TimesVN',11,15,7,TA_CENTER),('head','TimesVN-Bold',12,15.5,7,TA_JUSTIFY),
            ('cell','TimesVN',10.5,13,0,TA_JUSTIFY),('small','TimesVN-Italic',9.5,12,6,TA_JUSTIFY)]}
    story=[]
    def p(s,kind='body'):story.append(Paragraph(s,styles[kind]))
    def head(s):story.append(Spacer(1,5));p(s,'head')
    def table(rows,widths):
        data=[[Paragraph(escape(str(c)).replace('\n','<br/>'),styles['cell']) for c in r] for r in rows]
        tab=Table(data,colWidths=widths,repeatRows=1,hAlign='LEFT')
        tab.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('GRID',(0,0),(-1,-1),.5,colors.HexColor('#a6adb5')),
            ('BACKGROUND',(0,0),(-1,0),colors.HexColor('#edf0f3')),
            *[(side,(0,0),(-1,-1),7) for side in ['LEFTPADDING','RIGHTPADDING','TOPPADDING','BOTTOMPADDING']]]))
        story.extend([tab,Spacer(1,7)])
    T,A,B=escape(title),escape(artist),escape(album)
    p('HỢP ĐỒNG CẤP QUYỀN KHAI THÁC<br/>ÂM NHẠC TRÊN NỀN TẢNG SỐ','title')
    p('Số: '+code,'center')
    p('Mẫu thử nghiệm - Chưa ký kết. Dữ liệu DEMO, không có giá trị cấp quyền thực tế.','small')
    p(f'Hôm nay, ngày {start.day:02} tháng {start.month:02} năm {start.year}, tại Thành phố Hồ Chí Minh, các bên giả định gồm:')
    head('BÊN A: BÊN CẤP QUYỀN')
    p('<b>Tên đơn vị:</b> '+OWNER+'.')
    p('<b>Địa chỉ liên hệ:</b> Văn phòng Minh An, Thành phố Hồ Chí Minh (địa chỉ giả định).')
    p('<b>Đại diện:</b> Đại diện Minh An (nhân vật giả định). <b>Chức vụ:</b> Người phụ trách cấp quyền.')
    p('<b>Email:</b> rights@example.invalid.')
    head('BÊN B: BÊN NHẬN QUYỀN')
    p('<b>Tên đơn vị:</b> Đơn vị vận hành nền tảng Moodify (giả định).')
    p('<b>Địa chỉ liên hệ:</b> Văn phòng Moodify, Thành phố Hồ Chí Minh (địa chỉ giả định).')
    p('<b>Đại diện:</b> Đại diện Moodify (nhân vật giả định). <b>Chức vụ:</b> Người phụ trách khai thác nội dung.')
    p('<b>Email:</b> licensing@example.invalid.')
    p('Các bên thống nhất nội dung mẫu hợp đồng như sau:')
    head('Điều 1. Đối tượng và hình thức cấp quyền')
    p(f'1. Đối tượng là bản ghi bài hát <b>{T}</b>, nghệ sĩ thể hiện trong danh mục: <b>{A}</b>, album <b>{B}</b>. Thông tin nhận diện được ghi tại Phụ lục 01.')
    p('2. Hình thức cấp quyền: trực tiếp, không độc quyền. Bên cấp quyền giả định là <b>'+OWNER+'</b>. Không thông qua nhà phân phối trung gian.')
    p('3. Quyền chỉ có thể được xác lập từ căn cứ sở hữu hoặc ủy quyền hợp lệ. Mẫu này không chuyển giao quyền sở hữu tác phẩm, bản ghi hay quyền nhân thân; không xác nhận Bên A đại diện cho nghệ sĩ được ghi tên.')
    head('Điều 2. Phạm vi khai thác')
    p('1. Hình thức khai thác mô phỏng: <b>DIGITAL_STREAMING</b> - nghe trực tuyến trên website và ứng dụng Moodify, trong lãnh thổ Việt Nam. Hồ sơ vẫn là dữ liệu DEMO chưa ký kết.')
    p('2. Phạm vi mẫu gồm lưu trữ và chuyển đổi định dạng kỹ thuật để nghe trực tuyến. Tải để sở hữu, nghe ngoại tuyến, phối lại, quảng cáo và cấp lại quyền cho bên thứ ba không thuộc phạm vi này.')
    story.append(PageBreak())
    head('Điều 3. Thời hạn cấp quyền')
    p(f'1. Ngày bắt đầu mô phỏng: <b>{sl}</b>. Ngày hết hạn: <b>{el}</b>. Không tự động gia hạn. Các ngày này phải thống nhất với Phụ lục 01.')
    p('2. Bổ sung bài hát, thay đổi lãnh thổ hoặc phạm vi sử dụng cần hồ sơ riêng. Thời hạn trên là dữ liệu trình diễn, không chứng minh hiệu lực cấp quyền thật.')
    head('Điều 4. Chi phí mua quyền và thanh toán')
    p(f'1. <b>Chi phí mô phỏng cho bài hát {T}: 0 VND.</b> Bằng chữ: Không đồng. Đây là tổng chi phí giả định cho bản ghi tại Phụ lục 01 trong toàn bộ thời hạn nêu trên.')
    p('2. Không phát sinh nghĩa vụ thanh toán hoặc giao dịch chuyển khoản thực tế từ tài liệu này. Không chỉ định tài khoản ngân hàng thật.')
    p('3. Không áp dụng chia sẻ doanh thu trong hồ sơ mô phỏng. Khi lập hồ sơ thật, chi phí và điều kiện thanh toán cần được xác lập, kiểm duyệt riêng.')
    head('Điều 5. Trách nhiệm và hồ sơ quyền')
    p('1. Trước khi khai thác theo quyền thật, bên cấp quyền cần cung cấp bằng chứng quyền đối với bản ghi, tác phẩm, phần biểu diễn và văn bản chấp thuận hoặc ủy quyền tương ứng.')
    p('2. Bên nhận quyền ghi nhận tên bài hát và nghệ sĩ theo Phụ lục 01, không làm sai lệch nội dung và không sử dụng vượt phạm vi được xác lập hợp lệ.')
    p('3. Khi có khiếu nại có căn cứ, việc khai thác được tạm ngừng để kiểm tra. Thời hạn phản hồi trong tình huống mẫu là 05 ngày làm việc; tên trong danh mục không thay thế chứng cứ quyền.')
    head('Điều 6. Chấm dứt và giải quyết tranh chấp')
    p('Mẫu ghi nhận việc chấm dứt khi hết thời hạn hoặc theo thỏa thuận bằng văn bản; các bên ưu tiên thương lượng. Khi sử dụng thực tế, hiệu lực và cơ chế giải quyết phải được xác định trong hợp đồng ký kết hợp lệ. Bản DEMO này không xác lập giao dịch có hiệu lực.')
    p('Hồ sơ gồm 03 trang, bao gồm Phụ lục 01. Phần xác nhận để trống; không tạo chữ ký hay con dấu thay cho cá nhân hoặc tổ chức thật.')
    table([['ĐẠI DIỆN BÊN A','ĐẠI DIỆN BÊN B'],['Ký, ghi rõ họ tên và chức vụ\nChưa ký kết\n\n\nĐại diện Minh An (giả định)','Ký, ghi rõ họ tên và chức vụ\nChưa ký kết\n\n\nĐại diện Moodify (giả định)']],[width/2,width/2])
    story.append(PageBreak())
    p('PHỤ LỤC 01<br/>DANH MỤC BẢN GHI ĐƯỢC CẤP QUYỀN','title')
    p(f'Kèm theo hợp đồng số {code}, ngày {sl}','center')
    head('1. Thông tin nhận diện bản ghi')
    duration=t.get('duration_ms'); secs=round(float(duration)/1000) if isinstance(duration,(int,float)) and duration>0 else None
    duration_label=f'{secs//60:02} phút {secs%60:02} giây (làm tròn)' if secs else 'Chưa xác định trong danh mục'
    ref=t.get('spotify_id') or t.get('catalog_source_spotify_id')
    rows=[['Nội dung','Thông tin'],['Tên bài hát',title],['Nghệ sĩ thể hiện trong danh mục',artist]]
    if featured:rows.append(['Nghệ sĩ góp giọng theo danh mục',str(featured)])
    rows += [['Album theo danh mục',album],['Mã bản ghi Moodify',key]]
    if ref:rows.append(['Mã tham chiếu bản ghi',str(ref)+' (Spotify, theo nguồn)'])
    rows += [['Thời lượng bản ghi',duration_label],['Hình thức khai thác','Nghe nhạc trực tuyến trên nền tảng Moodify'],['Lãnh thổ','Việt Nam'],['Thời hạn mô phỏng',f'Từ ngày {sl} đến hết ngày {el}'],['Chi phí mô phỏng','0 VND']]
    table(rows,[width*.41,width*.59])
    head('2. Ghi nhận nghệ sĩ và phạm vi xác minh')
    p(f'Nghệ sĩ được ghi nhận: <b>{A}</b>.'+(' Nghệ sĩ góp giọng theo nguồn: '+escape(str(featured))+'.' if featured else ''))
    p('Danh sách trên ghi nhận nghệ sĩ theo danh mục bản ghi. Phạm vi quyền, tư cách đại diện và sự chấp thuận của từng bên phải được xác định trong tài liệu quyền kèm theo; tên nghệ sĩ không thay thế căn cứ cấp phép.')
    head('3. Điều kiện áp dụng')
    p('Phụ lục áp dụng riêng cho bản ghi có mã Moodify nêu trên. Bản phối hoặc phiên bản thu âm khác cần hồ sơ riêng. Phụ lục là bộ phận của mẫu hợp đồng, cùng trạng thái DEMO / chưa ký kết.')
    def footer(c,doc):
        c.saveState();c.setStrokeColor(colors.HexColor('#c6c9cf'));c.line(85.04,44,W-56.69,44)
        c.setFont('TimesVN',8);c.setFillColor(colors.HexColor('#555b64'))
        c.drawString(85.04,31,'Hợp đồng số: '+code)
        c.drawString(85.04,20,'DEMO - Chưa ký kết - Không có giá trị cấp quyền thực tế')
        c.drawRightString(W-56.69,31,f'Trang {doc.page}/3');c.restoreState()
    assert not target.exists(),'Immutable output exists'
    SimpleDocTemplate(str(target),pagesize=A4,leftMargin=85.04,rightMargin=56.69,topMargin=48,bottomMargin=58,
        title='Hợp đồng cấp quyền khai thác âm nhạc - '+title,author='Moodify - Mẫu thử nghiệm').build(story,onFirstPage=footer,onLaterPages=footer)
    reader=PdfReader(target);text='\n'.join(p.extract_text() or '' for p in reader.pages)
    assert len(reader.pages)==3,(key,title,len(reader.pages))
    normalized=re.sub(r'\s+',' ',text)
    for term in [code,title,key,'Điều 6.','PHỤ LỤC 01','Chưa ký kết','Không đồng']:
        assert re.sub(r'\s+',' ',term) in normalized,(key,term)
    for term in ['PHIẾU CẤP PHÉP','acquisition_cost_vnd','copyright_owner','license_type']:assert term not in text
    return hashlib.sha256(target.read_bytes()).hexdigest()

def main(a):
    source=json.loads(a.source.read_text(encoding='utf-8-sig'));old=json.loads(a.previous_manifest.read_text(encoding='utf-8-sig'))
    tracks={track_id(t):t for t in source['tracks']}
    assert old['simulated'] and old['not_real_rights']
    assert all(r['track_id'] in tracks for r in old['rows']),'Cohort changed'
    a.output.mkdir(parents=True,exist_ok=False);docs=a.output/'documents';docs.mkdir();fonts();rows=[]
    for row in old['rows']:
        live=[r for r in source['licenses'] if r['track_id']==row['track_id'] and r['distribution_contract_code']==row['contract_code'] and r['license_type']=='DEMO_SIMULATED']
        assert len(live)==1 and live[0]['document_songlicenses_url']==row['document_url'],'Live license changed'
        assert live[0]['issue_date']==row['issue_date'] and live[0]['expiry_date']==row['expiry_date']
        assert int(live[0]['acquisition_cost_vnd'])==int(row['acquisition_cost_vnd'])
        t=tracks[row['track_id']];new=dict(row)
        new.update(track_name=t['name'],artist_name=t.get('artist_name'),copyright_owner=OWNER,
            previous_document_url=row['document_url'],previous_pdf_sha256=row['pdf_sha256'],previous_copyright_owner=live[0]['copyright_owner'],
            license_id=live[0]['id'],document_version=VERSION,document_url='/uploads/licenses/demo-20261106-v2/'+row['track_id']+'.pdf',pages=3)
        new['pdf_sha256']=render(t,new,docs/(row['track_id']+'.pdf'));rows.append(new)
    manifest={**{k:v for k,v in old.items() if k!='rows'},'document_version':VERSION,
        'source_sha256':hashlib.sha256(a.source.read_bytes()).hexdigest(),'template_sha256':hashlib.sha256(a.template.read_bytes()).hexdigest(),'rows':rows}
    (a.output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'documents':len(rows),'pages':sum(r['pages'] for r in rows),'version':VERSION}))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--previous-manifest',type=Path,required=True);p.add_argument('--template',type=Path,required=True);main(p.parse_args())
