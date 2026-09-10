from __future__ import annotations
import copy, json, os, sys, time, traceback
from pathlib import Path
from dataclasses import asdict
from PySide6.QtCore import Qt, QTimer, QThread, Signal, QUrl, QSize, QSettings
from PySide6.QtGui import QAction, QKeySequence, QPixmap, QColor, QIcon, QPainter, QPen, QFont
from PySide6.QtWidgets import (QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QLabel,
    QPushButton, QToolButton, QListWidget, QListWidgetItem, QAbstractItemView, QSplitter, QTabWidget,
    QFormLayout, QLineEdit, QPlainTextEdit, QDoubleSpinBox, QSpinBox, QComboBox, QCheckBox, QSlider,
    QScrollArea, QFrame, QFileDialog, QMessageBox, QDialog, QDialogButtonBox, QProgressBar, QStackedWidget)
from PySide6.QtMultimedia import QMediaPlayer, QAudioOutput
from PySide6.QtMultimediaWidgets import QVideoWidget
from .model import Project, Clip, Grade, Overlay, History, PRESETS, TRANSITIONS, timecode, subtract_ranges, uid
from .engine import Runner, Renderer, Cancelled, probe, thumbnail, color_preview, app_dir, resources, silence_ranges
from .ai import API, Planner, UnconfirmedRequest
from .diagnostics import RunLog, redact
from .jobs_ui import JobDialog

STYLE='''
QWidget { background:#111722; color:#dce5f0; font-size:13px; }
QMainWindow, QDialog { background:#111722; }
QLabel { background:transparent; }
QLabel[muted="true"] { color:#8b9bb2; font-size:12px; }
QLabel[heading="true"] { font-size:19px; font-weight:650; color:#f4f7fc; }
QPushButton, QToolButton { background:#232e40; border:1px solid #304058; border-radius:8px; padding:8px 12px; }
QPushButton:hover, QToolButton:hover { background:#2d3b51; border-color:#6addcf; }
QPushButton:pressed { background:#354c62; }
QPushButton:disabled { color:#5a6b80; background:#192231; border-color:#233043; }
QPushButton[primary="true"] { background:#80e4d6; color:#0f2827; border:0; font-weight:650; }
QPushButton[primary="true"]:hover { background:#a0f0e4; }
QPushButton[primary="true"]:disabled { background:#34524f; color:#89a5a1; }
QPushButton[quiet="true"] { background:transparent; border-color:transparent; color:#a9bbd3; }
QLineEdit,QPlainTextEdit,QSpinBox,QDoubleSpinBox,QComboBox { background:#192231; border:1px solid #304058; border-radius:7px; padding:7px; selection-background-color:#357b7c; }
QLineEdit:focus,QPlainTextEdit:focus { border-color:#80e4d6; }
QComboBox::drop-down { border:0; width:22px; }
QComboBox QAbstractItemView { background:#202c3e; selection-background-color:#315158; }
QListWidget { background:#151d2a; border:1px solid #28364b; border-radius:10px; outline:0; padding:5px; }
QListWidget::item { padding:8px; border-radius:7px; margin:3px; }
QListWidget::item:selected { background:#263f48; color:#abf1e6; }
QListWidget::item:hover { background:#263347; }
QTabWidget::pane { border:1px solid #28364b; border-radius:9px; }
QTabBar::tab { background:#192231; padding:10px 12px; color:#98a9c2; }
QTabBar::tab:selected { color:#91eddf; border-bottom:2px solid #80e4d6; }
QScrollArea { border:0; background:transparent; }
QScrollBar:vertical { width:8px; background:#151d2a; }
QScrollBar::handle:vertical { background:#35445a; border-radius:4px; min-height:30px; }
QScrollBar:horizontal { height:8px; background:#151d2a; }
QScrollBar::handle:horizontal { background:#35445a; border-radius:4px; min-width:30px; }
QScrollBar::add-line,QScrollBar::sub-line { height:0; width:0; }
QSplitter::handle { background:#283449; width:1px; height:1px; }
QSlider::groove:horizontal { height:5px; background:#2c3b50; border-radius:2px; }
QSlider::handle:horizontal { background:#80e4d6; width:12px; margin:-4px 0; border-radius:6px; }
QSlider::sub-page:horizontal { background:#5fbfb5; border-radius:2px; }
QCheckBox { spacing:8px; padding:4px 0; }
QCheckBox::indicator { width:16px; height:16px; border:1px solid #596e87; border-radius:4px; background:#192231; }
QCheckBox::indicator:checked { background:#80e4d6; border-color:#80e4d6; image:url(:/qt-project.org/styles/commonstyle/images/standardbutton-apply-16.png); }
QProgressBar { border:0; background:#263347; border-radius:4px; height:7px; text-align:center; }
QProgressBar::chunk { background:#80e4d6; border-radius:4px; }
QMenuBar,QMenu { background:#192231; }
QMenu::item:selected { background:#315158; }
QStatusBar { color:#8b9bb2; }
QToolTip { color:#e1eaf5; background:#273548; border:1px solid #526a82; padding:5px; }
'''

def button(text,slot=None,primary=False,quiet=False):
    b=QPushButton(text);b.setProperty('primary',primary);b.setProperty('quiet',quiet)
    if slot:b.clicked.connect(slot)
    return b
def label(text,muted=False,heading=False):
    w=QLabel(text);w.setWordWrap(True);w.setProperty('muted',muted);w.setProperty('heading',heading);return w
def combo(items):
    w=QComboBox()
    for value in items:
        if isinstance(value,tuple):w.addItem(value[0],value[1])
        else:w.addItem(str(value),value)
    return w
def number(low,high,value=0,step=.1,decimals=2,suffix=''):
    w=QDoubleSpinBox();w.setRange(low,high);w.setDecimals(decimals);w.setSingleStep(step);w.setValue(value);w.setSuffix(suffix);return w
def row(*widgets):
    w=QWidget();l=QHBoxLayout(w);l.setContentsMargins(0,0,0,0);l.setSpacing(6)
    for x in widgets:l.addWidget(x)
    return w
def scroll(widget):
    s=QScrollArea();s.setWidgetResizable(True);s.setWidget(widget);return s
def select_data(widget,value):
    i=widget.findData(value)
    if i>=0:widget.setCurrentIndex(i)

class Worker(QThread):
    progress=Signal(int,str);event=Signal(object);succeeded=Signal(object);failed=Signal(object);cancelled=Signal()
    def __init__(self,task,title='Обработка'):
        super().__init__();self.task=task;self.journal=RunLog(app_dir()/'logs',title)
        self.runner=Runner(event=self.report)
    def report(self,event):
        if event['type'] not in ('media',):
            message=event.get('message') or event.get('name')
            if message:
                line=self.journal.write(event['type']+' · '+str(message),self.runner.secrets)
                self.event.emit({'type':'line','message':line})
        self.event.emit(event)
    def milestone(self,n,s):
        self.report({'type':'log','message':s});self.progress.emit(n,s)
    def run(self):
        try:
            result=self.task(self.runner,self.milestone);self.runner.check();self.succeeded.emit(result)
        except Cancelled:
            self.report({'type':'log','message':'Операция остановлена пользователем.'});self.cancelled.emit()
        except Exception as exc:
            self.report({'type':'log','message':traceback.format_exc()});self.failed.emit(exc)


class SettingsDialog(QDialog):
    def __init__(self,parent):
        super().__init__(parent);self.setWindowTitle('OpenAI и расходы');self.resize(510,340)
        lay=QVBoxLayout(self);lay.addWidget(label('Монтаж по заданию',heading=True))
        lay.addWidget(label('Исходные видео остаются на Mac. Для ИИ отправляются сжатый звук, расшифровка и отдельные кадры. Ручной монтаж работает без ключа.',True))
        self.key=QLineEdit(parent.api_key);self.key.setEchoMode(QLineEdit.EchoMode.Password);self.key.setPlaceholderText('API-ключ OpenAI')
        self.budget=number(1,1000,parent.settings.value('budget',10,type=float),1,0,' $')
        form=QFormLayout();form.addRow('Ключ',self.key);form.addRow('Бюджет одного запуска',self.budget);lay.addLayout(form)
        lay.addWidget(label('Ключ сохраняется в Связке ключей macOS. Подписка ChatGPT не оплачивает API. Бюджет — предохранитель по оценке токенов; окончательное списание показывает OpenAI.',True))
        links=label('<a style="color:#80e4d6" href="https://platform.openai.com/api-keys">Получить ключ</a> · <a style="color:#80e4d6" href="https://platform.openai.com/usage">Расходы OpenAI</a>');links.setOpenExternalLinks(True);lay.addWidget(links)
        self.check_worker=None
        self.check_button=button('Проверить ключ и подключение',self.check_key);lay.addWidget(self.check_button)
        self.check_status=label('Проверка доступа к модели без запуска монтажа.',True);lay.addWidget(self.check_status)
        lay.addWidget(label('Долгие монтажные запросы выполняются в фоне OpenAI. Ответ временно хранится для получения результата; постоянное хранение отключено.',True))
        self.box=QDialogButtonBox(QDialogButtonBox.StandardButton.Save|QDialogButtonBox.StandardButton.Cancel);self.box.accepted.connect(self.accept);self.box.rejected.connect(self.reject);lay.addWidget(self.box)
    def check_key(self):
        if self.check_worker:return
        key=self.key.text().strip()
        if not key:self.check_status.setText('Сначала вставь API-ключ в поле выше.');return
        self.check_button.setEnabled(False);self.key.setEnabled(False);self.box.setEnabled(False);self.check_status.setText('Проверяем OpenAI…')
        self.check_worker=Worker(lambda r,p:API(key,r).check_connection(),'Проверка OpenAI')
        self.check_worker.succeeded.connect(lambda message:self.check_status.setText(message))
        self.check_worker.failed.connect(lambda error:self.check_status.setText(redact(error,[key])+'\nЖурнал: '+str(self.check_worker.journal.path)))
        self.check_worker.finished.connect(self.check_finished);self.check_worker.start()
    def check_finished(self):
        worker=self.check_worker;self.check_worker=None;self.check_button.setEnabled(True);self.key.setEnabled(True);self.box.setEnabled(True)
        worker.deleteLater()
    def reject(self):
        if self.check_worker:
            self.check_worker.runner.cancel();self.check_status.setText('Завершаем сетевую проверку…');return
        super().reject()
    def closeEvent(self,event):
        if self.check_worker:self.reject();event.ignore()
        else:event.accept()

class OverlayDialog(QDialog):
    def __init__(self,clip,media,parent=None):
        super().__init__(parent);self.setWindowTitle('Надписи и графика');self.resize(620,560)
        self.overlays=copy.deepcopy(clip.overlays);self.duration=clip.duration;self.current=-1
        layout=QVBoxLayout(self);layout.addWidget(label('Вставки поверх видео',heading=True))
        self.items=QListWidget();self.items.setMaximumHeight(135);self.items.currentRowChanged.connect(self.load);layout.addWidget(self.items)
        layout.addWidget(row(button('+ Вставка',self.add),button('Удалить',self.remove)))
        form=QFormLayout();self.text=QPlainTextEdit();self.text.setMaximumHeight(74);self.text.setPlaceholderText('35 Вт  /  1200p  /  FSR Quality')
        self.kind=combo([('Карточка параметров','card'),('Заголовок','title'),('Подпись','caption'),('График','chart'),('Своя картинка','image')])
        self.position=combo([('Снизу','bottom'),('По центру','center'),('Сверху','top')])
        self.start=number(0,clip.duration,0,.1,2,' с');self.end=number(0,clip.duration,min(4,clip.duration),.1,2,' с')
        self.asset=combo([('Без картинки','')]+[(m.name,m.path) for m in media if m.image])
        self.chart=QPlainTextEdit();self.chart.setMaximumHeight(70);self.chart.setPlaceholderText('35 Вт = 62\n20 Вт = 46')
        form.addRow('Текст',self.text);form.addRow('Вид',self.kind);form.addRow('Положение',self.position);form.addRow('Появление / конец',row(self.start,self.end));form.addRow('Картинка',self.asset);form.addRow('Данные графика',self.chart);layout.addLayout(form)
        layout.addWidget(label('Время отсчитывается от начала фрагмента. Вставки появляются и исчезают плавно. Картинки сначала добавь в исходники.',True))
        box=QDialogButtonBox(QDialogButtonBox.StandardButton.Save|QDialogButtonBox.StandardButton.Cancel);box.accepted.connect(self.finish);box.rejected.connect(self.reject);layout.addWidget(box)
        self.rebuild()
        if self.overlays:self.items.setCurrentRow(0)
        else:self.add()
    def commit(self):
        if not 0<=self.current<len(self.overlays):return
        o=self.overlays[self.current];o.text=self.text.toPlainText();o.style=self.kind.currentData();o.position=self.position.currentData();o.start=self.start.value();o.end=max(o.start,self.end.value());o.asset=self.asset.currentData();o.chart=[]
        if o.style=='chart':
            for line in self.chart.toPlainText().splitlines():
                if '=' not in line:continue
                name,value=line.rsplit('=',1)
                try:o.chart.append({'label':name.strip(),'value':max(0,float(value.strip().replace(',','.')))})
                except ValueError:raise ValueError('В графике укажи число после знака =')
    def rebuild(self):
        self.items.blockSignals(True);self.items.clear()
        for i,o in enumerate(self.overlays):self.items.addItem(f'{i+1:02}   {o.text[:55] or "Картинка / новая вставка"}   ·   {o.start:g}–{o.end:g} с')
        self.items.blockSignals(False)
    def load(self,i):
        try:self.commit()
        except ValueError as e:QMessageBox.warning(self,'График',str(e))
        self.current=i
        if not 0<=i<len(self.overlays):return
        o=self.overlays[i];self.text.setPlainText(o.text);select_data(self.kind,o.style);select_data(self.position,o.position);select_data(self.asset,o.asset);self.start.setValue(o.start);self.end.setValue(o.end);self.chart.setPlainText('\n'.join(f'{r["label"]} = {r["value"]:g}' for r in o.chart))
    def add(self):
        if len(self.overlays)>=12:return
        try:self.commit()
        except ValueError as e:QMessageBox.warning(self,'График',str(e));return
        self.overlays.append(Overlay(end=min(4,self.duration)));self.current=-1;self.rebuild();self.items.setCurrentRow(len(self.overlays)-1)
    def remove(self):
        if 0<=self.current<len(self.overlays):self.overlays.pop(self.current)
        self.current=-1;self.rebuild()
        if self.overlays:self.items.setCurrentRow(0)
    def finish(self):
        try:
            self.commit()
            if any(o.style=='image' and not o.asset for o in self.overlays):raise ValueError('Выбери картинку для вставки')
        except ValueError as e:QMessageBox.warning(self,'Проверь вставку',str(e));return
        self.accept()

class MainWindow(QMainWindow):
    def __init__(self,restore=True):
        super().__init__();self.setWindowTitle('Oldy Cut');self.resize(1400,880);self.setMinimumSize(1100,700)
        self.settings=QSettings('Oldy','Cut');self.project=Project();self.history=History();self.project_path='';self.loading=False;self.worker=None;self.api_key='';self.selected_id=None;self.preview_mode=False;self.dirty=False;self.job_dialog=None
        try:
            import keyring
            self.api_key=keyring.get_password('Oldy Cut','OpenAI') or ''
        except Exception:pass
        self.player=QMediaPlayer(self);self.audio=QAudioOutput(self);self.player.setAudioOutput(self.audio);self.audio.setVolume(.85)
        self._build();self._menus();self.player.positionChanged.connect(self.position_changed);self.player.durationChanged.connect(lambda d:self.seek.setMaximum(max(1,d)))
        self.player.mediaStatusChanged.connect(self.media_status);self.player.errorOccurred.connect(lambda e,s:self.statusBar().showMessage('Предпросмотр: '+s,15000))
        self.setAcceptDrops(True)
        if restore:
            auto=app_dir()/'last.oldycut'
            if auto.exists():
                try:self.project=Project.load(auto);self.statusBar().showMessage('Восстановлен последний проект',8000)
                except Exception:pass
        self.refresh()
    def _build(self):
        central=QWidget();self.setCentralWidget(central);main=QVBoxLayout(central);main.setContentsMargins(18,14,18,12);main.setSpacing(12)
        logo=QLabel('<span style="color:#e8f1fd;font-size:26px;font-weight:800;letter-spacing:2px">OLDY</span> <span style="color:#80e4d6;font-size:26px;font-weight:300">/ CUT</span>')
        self.project_label=label('Новый обзор',True);top=QHBoxLayout();top.addWidget(logo);top.addSpacing(22);top.addWidget(self.project_label);top.addStretch()
        top.addWidget(button('Сохранить проект',self.save_project));top.addWidget(button('OpenAI',self.settings_dialog));self.export_button=button('Экспорт видео',self.export_video,True);top.addWidget(self.export_button);main.addLayout(top)
        self.splitter=QSplitter(Qt.Orientation.Horizontal);main.addWidget(self.splitter,1)
        left=QWidget();ll=QVBoxLayout(left);ll.setContentsMargins(0,0,12,0);ll.addWidget(label('Исходники',heading=True));ll.addWidget(label('Видео, фотографии, графика',True))
        ll.addWidget(button('+ Добавить файлы',self.choose_import,True));self.media_list=QListWidget();self.media_list.setIconSize(QSize(84,50));self.media_list.setWordWrap(True);self.media_list.itemDoubleClicked.connect(self.preview_media);ll.addWidget(self.media_list,1)
        ll.addWidget(button('На монтажную ленту',self.append_selected_media));ll.addWidget(button('Найти исходник…',self.relink_media,quiet=True));ll.addWidget(label('Файлы читаются с диска. Оригиналы остаются целыми.',True));self.splitter.addWidget(left)
        center=QWidget();cl=QVBoxLayout(center);cl.setContentsMargins(8,0,12,0)
        self.viewer_title=label('ПРОСМОТР · ИСХОДНИК',True);cl.addWidget(self.viewer_title)
        self.viewer=QStackedWidget();self.viewer.setMinimumHeight(230);self.still=QLabel();self.still.setAlignment(Qt.AlignmentFlag.AlignCenter);self.still.setStyleSheet('background:#090e16;border:1px solid #263449;border-radius:12px;color:#64758b;font-size:20px;');self.still.setText('Добавь видео, чтобы начать')
        self.video=QVideoWidget();self.player.setVideoOutput(self.video);self.viewer.addWidget(self.still);self.viewer.addWidget(self.video);cl.addWidget(self.viewer,1)
        transport=QHBoxLayout();self.play_button=button('▶',self.toggle_play);self.play_button.setFixedWidth(44);transport.addWidget(self.play_button)
        self.seek=QSlider(Qt.Orientation.Horizontal);self.seek.setRange(0,1);self.seek.sliderMoved.connect(self.player.setPosition);transport.addWidget(self.seek,1);self.clock=label('00:00.00',True);self.clock.setMinimumWidth(80);transport.addWidget(self.clock);cl.addLayout(transport)
        self.preview_buttons=row(button('Превью фрагмента',lambda:self.render_preview(True)),button('Превью монтажа',lambda:self.render_preview(False)));cl.addWidget(self.preview_buttons)
        head=QHBoxLayout();head.addWidget(label('Монтажная лента',heading=True));head.addStretch();self.duration_label=label('0 фрагментов',True);head.addWidget(self.duration_label);cl.addLayout(head)
        self.timeline=QListWidget();self.timeline.setViewMode(QListWidget.ViewMode.IconMode);self.timeline.setFlow(QListWidget.Flow.LeftToRight);self.timeline.setWrapping(False);self.timeline.setMovement(QListWidget.Movement.Static);self.timeline.setResizeMode(QListWidget.ResizeMode.Adjust);self.timeline.setIconSize(QSize(130,72));self.timeline.setGridSize(QSize(155,137));self.timeline.setFixedHeight(154);self.timeline.setWordWrap(True);self.timeline.currentItemChanged.connect(self.clip_selected);cl.addWidget(self.timeline)
        actions=QHBoxLayout();actions.addWidget(button('←',lambda:self.move_clip(-1)));actions.addWidget(button('→',lambda:self.move_clip(1)));actions.addWidget(button('Разрезать',self.split_clip));self.cut_button=button('Исключить',self.toggle_clip);actions.addWidget(self.cut_button);actions.addStretch();self.show_removed=QCheckBox('Вырезки');self.show_removed.toggled.connect(lambda:self.refresh_timeline());actions.addWidget(self.show_removed);cl.addLayout(actions)
        self.splitter.addWidget(center)
        self.tabs=QTabWidget();self.tabs.addTab(scroll(self.ai_panel()),'ИИ');self.tabs.addTab(scroll(self.clip_panel()),'Монтаж');self.tabs.addTab(scroll(self.grade_panel()),'Цвет');self.tabs.addTab(scroll(self.export_panel()),'Вывод');self.tabs.setMinimumWidth(330);self.splitter.addWidget(self.tabs);self.splitter.setSizes([245,770,345])
        bottom=QHBoxLayout();self.status=label('Можно начать с ручного монтажа или задания для ИИ.',True);bottom.addWidget(self.status,1);self.progress=QProgressBar();self.progress.setFixedWidth(180);self.progress.setTextVisible(False);self.progress.hide();bottom.addWidget(self.progress);self.cancel_button=button('Отмена',self.cancel_job);self.cancel_button.hide();bottom.addWidget(self.cancel_button);self.job_button=button('Ход работы',self.show_job);bottom.addWidget(self.job_button);main.addLayout(bottom)
    def ai_panel(self):
        w=QWidget();l=QVBoxLayout(w);l.setContentsMargins(16,18,16,16);l.addWidget(label('Твой монтажёр',heading=True));l.addWidget(label('Опиши, какой ролик хочешь получить. После сборки любую правку можно изменить.',True))
        self.prompt=QPlainTextEdit();self.prompt.setMinimumHeight(180);self.prompt.textChanged.connect(self.prompt_changed);l.addWidget(self.prompt)
        self.check_audio=QCheckBox('Проверить подозрительные звуки');self.check_audio.setChecked(True);l.addWidget(self.check_audio);l.addWidget(label('Проверяются короткие промежутки без слов. Сомнительные кашель и вздохи остаются для просмотра.',True))
        l.addWidget(button('Собрать с GPT‑6',self.ai_analyze,True));l.addWidget(label('Расшифровка, отдельные кадры и короткие звуковые отрывки будут отправлены в OpenAI. Тариф API оплачивается отдельно.',True))
        l.addSpacing(14);l.addWidget(label('Исправить готовый монтаж',heading=True));self.refine=QPlainTextEdit();self.refine.setPlaceholderText('Например: верни вступление и сделай цвет чуть теплее.');self.refine.setMaximumHeight(90);l.addWidget(self.refine);l.addWidget(button('Применить задание',self.ai_refine))
        l.addWidget(button('Убрать длинную тишину · без ИИ',self.remove_silence));l.addWidget(label('Локальная вырезка тишины также удаляет молчаливые игровые сцены. Используй для разговорных исходников.',True))
        self.notes=QPlainTextEdit();self.notes.setReadOnly(True);self.notes.setPlaceholderText('Здесь появятся заметки по монтажу');self.notes.setMinimumHeight(100);l.addWidget(self.notes);self.cost=label('API: 0.00 $',True);l.addWidget(self.cost);return w
    def clip_panel(self):
        w=QWidget();l=QVBoxLayout(w);l.setContentsMargins(16,18,16,16);l.addWidget(label('Фрагмент',heading=True));self.clip_name=QLineEdit();self.clip_name.setPlaceholderText('Название фрагмента');l.addWidget(self.clip_name)
        form=QFormLayout();self.trim_in=number(0,1e7,0,.1,3,' с');self.trim_out=number(0,1e7,1,.1,3,' с');self.speed=number(.25,4,1,.05,2,' ×');self.volume=number(0,300,100,5,0,' %')
        self.transition=combo([(v,k) for k,v in TRANSITIONS.items()]);self.transition_duration=number(0,2,.35,.05,2,' с')
        self.crop_x=number(0,100,50,5,0,' %');self.crop_y=number(0,100,50,5,0,' %')
        form.addRow('Начало исходника',self.trim_in);form.addRow('Конец исходника',self.trim_out);form.addRow('Скорость',self.speed);form.addRow('Громкость',self.volume);form.addRow('Входящий переход',self.transition);form.addRow('Длительность',self.transition_duration);l.addLayout(form)
        l.addWidget(button('Начало по курсору',lambda:self.set_trim_cursor(True)));l.addWidget(button('Конец по курсору',lambda:self.set_trim_cursor(False)));l.addWidget(button('Сохранить правки фрагмента',self.apply_clip,True));l.addWidget(button('Надписи и графика…',self.edit_overlays));self.overlay_count=label('Вставок: 0',True);l.addWidget(self.overlay_count)
        l.addSpacing(10);l.addWidget(label('Кадрирование',heading=True));crop=QFormLayout();crop.addRow('По горизонтали',self.crop_x);crop.addRow('По вертикали',self.crop_y);l.addLayout(crop);l.addWidget(label('Положение кадра применяется при заполнении без полей. После изменения нажми «Сохранить правки».',True))
        self.clip_reason=label('',True);l.addWidget(self.clip_reason);l.addStretch();return w
    def grade_panel(self):
        w=QWidget();l=QVBoxLayout(w);l.setContentsMargins(16,18,16,16);l.addWidget(label('Цвет и фактура',heading=True));self.preset=combo(PRESETS);l.addWidget(self.preset);l.addWidget(button('Применить пресет',self.apply_preset))
        self.grade_fields={};form=QFormLayout()
        for name,title,low,high,value,step in [('exposure','Экспозиция',-2,2,0,.1),('contrast','Контраст',.3,2,1,.05),('saturation','Насыщенность',0,2,1,.05),('temperature','Теплота',-.5,.5,0,.025),('gamma','Гамма',.3,3,1,.05),('sharpen','Резкость',0,2,0,.1),('denoise','Подавление шума',0,6,0,.2)]:
            field=number(low,high,value,step);self.grade_fields[name]=field;form.addRow(title,field)
        l.addLayout(form);self.vignette=QCheckBox('Мягкая виньетка');l.addWidget(self.vignette)
        self.lut=QLineEdit();self.lut.setReadOnly(True);self.lut.setPlaceholderText('LUT .cube не выбран');l.addWidget(self.lut);l.addWidget(row(button('Добавить LUT',self.choose_lut),button('Сброс LUT',lambda:self.lut.clear())))
        l.addWidget(button('Применить и показать кадр',self.apply_grade,True));l.addWidget(button('Этот цвет всем фрагментам',self.grade_all));l.addWidget(label('Коррекция видна на обработанном кадре и в собранном превью. Просмотр исходника показывает оригинал.',True));l.addStretch();return w
    def export_panel(self):
        w=QWidget();l=QVBoxLayout(w);l.setContentsMargins(16,18,16,16);l.addWidget(label('Готовое видео',heading=True));form=QFormLayout()
        self.format=combo([('MP4','mp4'),('MOV','mov'),('MKV','mkv')]);self.codec=combo([('H.264 · совместимый','h264'),('HEVC · компактнее','hevc')]);self.aspect=combo([('16:9 · горизонтально','landscape'),('9:16 · вертикально','portrait'),('1:1 · квадрат','square'),('Свои размеры','custom')]);self.resolution=combo([('720p',720),('1080p · Full HD',1080),('1440p',1440),('2160p · 4K',2160)]);select_data(self.resolution,1080)
        self.width=number(128,7680,1920,2,0);self.height=number(128,7680,1080,2,0);self.fps=combo([24,25,29.97,30,50,59.94,60]);select_data(self.fps,30)
        self.fit=combo([('Вписать целиком','contain'),('Заполнить без полей','cover')]);self.quality=combo(['Высокое','Максимальное','Компактное']);self.bitrate=number(0,250,0,1,2,' Мбит/с');self.target_size=number(0,100000,0,100,0,' МБ')
        form.addRow('Формат',self.format);form.addRow('Кодек',self.codec);form.addRow('Кадр',self.aspect);form.addRow('Разрешение',self.resolution);form.addRow('Ширина × высота',row(self.width,self.height));form.addRow('Кадров в секунду',self.fps);form.addRow('Заполнение',self.fit);form.addRow('Качество',self.quality);form.addRow('Битрейт · 0 = авто',self.bitrate);form.addRow('Размер · 0 = авто',self.target_size);l.addLayout(form)
        self.aspect.currentIndexChanged.connect(self.dimensions_changed);self.resolution.currentIndexChanged.connect(self.dimensions_changed)
        l.addWidget(label('Размер приблизительный и имеет приоритет над битрейтом. Чем меньше файл, тем ниже детализация. 4K не добавит деталей исходнику 1080p.',True))
        self.normalize=QCheckBox('Выровнять громкость');self.normalize.setChecked(True);l.addWidget(self.normalize);l.addWidget(label('Музыка',heading=True));self.music=QLineEdit();self.music.setReadOnly(True);self.music.setPlaceholderText('Без фоновой музыки');l.addWidget(self.music);l.addWidget(row(button('Выбрать файл',self.choose_music),button('Убрать',lambda:self.music.clear())))
        self.music_volume=number(0,100,12,1,0,' %');ml=QFormLayout();ml.addRow('Громкость музыки',self.music_volume);l.addLayout(ml);self.duck=QCheckBox('Приглушать музыку под речь');self.duck.setChecked(True);l.addWidget(self.duck);l.addWidget(button('Сохранить видео…',self.export_video,True));l.addWidget(label('Рендер идёт на Mac. Для длинного ролика оставь свободное место для временных файлов и подключи питание.',True));l.addStretch();return w
    def _menus(self):
        f=self.menuBar().addMenu('Файл');edit=self.menuBar().addMenu('Правка');view=self.menuBar().addMenu('Монтаж');help_menu=self.menuBar().addMenu('Помощь')
        def add(menu,title,slot,key=None):
            a=QAction(title,self);a.triggered.connect(slot)
            if key:a.setShortcut(key)
            menu.addAction(a);return a
        add(f,'Новый проект',self.new_project,QKeySequence.StandardKey.New);add(f,'Открыть проект…',self.open_project,QKeySequence.StandardKey.Open);add(f,'Добавить исходники…',self.choose_import,'Ctrl+I');add(f,'Сохранить проект',self.save_project,QKeySequence.StandardKey.Save);add(f,'Сохранить проект как…',lambda:self.save_project(True),QKeySequence.StandardKey.SaveAs);add(f,'Экспорт видео…',self.export_video,'Ctrl+E')
        add(edit,'Отменить',lambda:self.undo(False),QKeySequence.StandardKey.Undo);add(edit,'Повторить',lambda:self.undo(True),QKeySequence.StandardKey.Redo)
        add(view,'Разрезать по курсору',self.split_clip,'Ctrl+B');add(view,'Настройки OpenAI',self.settings_dialog);add(view,'Открыть папку проекта',lambda:__import__('PySide6.QtGui',fromlist=['QDesktopServices']).QDesktopServices.openUrl(QUrl.fromLocalFile(str(Path(self.project_path).parent if self.project_path else app_dir()))))
        add(help_menu,'Как работать',self.help);add(help_menu,'О программе',lambda:QMessageBox.information(self,'Oldy Cut 0.1','Локальный монтаж видео с редактируемым планом ИИ.\nPython · Qt · FFmpeg · OpenAI\n\nOpenAI обрабатывает отправленные данные по условиям API.\nЛицензии компонентов находятся в папке приложения.'))
    def current_clip(self):return next((c for c in self.project.clips if c.id==self.selected_id),None)
    def before(self):self.history.push(self.project)
    def changed(self):
        self.dirty=True
        try:self.project.save(app_dir()/'last.oldycut')
        except Exception as e:self.statusBar().showMessage('Не удалось сохранить черновик: '+str(e),10000)
        self.project_label.setText(self.project.name+'  •');self.setWindowTitle(self.project.name+' — Oldy Cut')
    def prompt_changed(self):
        if not self.loading:self.project.prompt=self.prompt.toPlainText();self.changed()
    def refresh(self):
        self.loading=True;self.project_label.setText(self.project.name+('  •' if self.dirty else ''));self.setWindowTitle(self.project.name+' — Oldy Cut');self.prompt.setPlainText(self.project.prompt);self.notes.setPlainText('\n\n'.join(dict.fromkeys(self.project.notes)));self.cost.setText(f'Учтено API в проекте: ≈ {self.project.api_spent:.2f} $')
        self.media_list.clear()
        for m in self.project.media:
            item=QListWidgetItem(f'{m.name}\n{m.width} × {m.height}  ·  {"Картинка" if m.image else timecode(m.duration)}');item.setData(Qt.ItemDataRole.UserRole,m.id)
            if m.thumb and Path(m.thumb).exists():item.setIcon(QIcon(m.thumb))
            if not Path(m.path).exists():item.setForeground(QColor('#f9a58c'));item.setToolTip('Исходник перемещён. Нажми «Найти исходник».')
            else:item.setToolTip(m.path)
            self.media_list.addItem(item)
        self.load_export();self.loading=False;self.refresh_timeline();self.load_clip()
    def refresh_timeline(self):
        keep=self.selected_id;self.timeline.blockSignals(True);self.timeline.clear()
        for n,c in enumerate(self.project.clips):
            if not c.enabled and not self.show_removed.isChecked():continue
            m=self.project.media_for(c);item=QListWidgetItem(f'{n+1:02}  {c.label or m.name}\n{timecode(c.duration)}'+('  ·  вырезка' if not c.enabled else ''));item.setData(Qt.ItemDataRole.UserRole,c.id);item.setToolTip(f'{m.name}\n{timecode(c.start)} → {timecode(c.end)}\n{c.reason}')
            if m.thumb and Path(m.thumb).exists():item.setIcon(QIcon(m.thumb))
            if not c.enabled:item.setForeground(QColor('#7b8697'))
            self.timeline.addItem(item)
            if c.id==keep:self.timeline.setCurrentItem(item)
        self.timeline.blockSignals(False)
        if self.timeline.currentRow()<0 and self.timeline.count():self.timeline.setCurrentRow(0)
        self.duration_label.setText(f'{len(self.project.active())} фр.  ·  {timecode(self.project.duration)}');self.cut_button.setText('Вернуть' if self.current_clip() and not self.current_clip().enabled else 'Исключить')
    def clip_selected(self,item,previous=None):
        if not item:return
        self.selected_id=item.data(Qt.ItemDataRole.UserRole);self.load_clip();c=self.current_clip()
        if c:self.load_source(self.project.media_for(c),c.start)
    def load_clip(self):
        c=self.current_clip()
        if not c:return
        self.loading=True;self.clip_name.setText(c.label);self.trim_in.setValue(c.start);self.trim_out.setValue(c.end);self.speed.setValue(c.speed);self.volume.setValue(c.volume*100);select_data(self.transition,c.transition);self.transition_duration.setValue(c.transition_seconds);self.crop_x.setValue(c.crop_x*100);self.crop_y.setValue(c.crop_y*100)
        for k,w in self.grade_fields.items():w.setValue(getattr(c.grade,k))
        self.vignette.setChecked(c.grade.vignette);self.lut.setText(c.grade.lut);self.overlay_count.setText(f'Вставок: {len(c.overlays)}');self.clip_reason.setText(c.reason);self.cut_button.setText('Вернуть' if not c.enabled else 'Исключить');self.loading=False
    def preview_media(self,item):
        mid=item.data(Qt.ItemDataRole.UserRole);m=next(m for m in self.project.media if m.id==mid);self.load_source(m,0)
    def load_source(self,m,start=0):
        self.player.pause();self.preview_mode=False;self.source_start=start;self.viewer_title.setText('ИСХОДНИК · '+m.name);self.play_button.setText('▶')
        self.player.setSource(QUrl.fromLocalFile(m.path));self.player.setPosition(int(start*1000));self.display_still(m.thumb if m.thumb else '',m.name)
    def display_still(self,path,fallback='Предпросмотр'):
        self.viewer.setCurrentWidget(self.still)
        pix=QPixmap(path) if path else QPixmap()
        if pix.isNull():self.still.setPixmap(QPixmap());self.still.setText(fallback)
        else:self.still.setPixmap(pix.scaled(self.viewer.size()-QSize(8,8),Qt.AspectRatioMode.KeepAspectRatio,Qt.TransformationMode.SmoothTransformation))
    def media_status(self,status):
        if status==QMediaPlayer.MediaStatus.LoadedMedia:
            if not self.preview_mode:self.player.setPosition(int(getattr(self,'source_start',0)*1000))
    def toggle_play(self):
        if self.player.source().isEmpty():return
        if self.player.playbackState()==QMediaPlayer.PlaybackState.PlayingState:self.player.pause();self.play_button.setText('▶')
        else:self.viewer.setCurrentWidget(self.video);self.player.play();self.play_button.setText('Ⅱ')
    def position_changed(self,pos):
        if not self.seek.isSliderDown():self.seek.setValue(pos)
        self.clock.setText(timecode(pos/1000))
    def choose_import(self):
        paths,_=QFileDialog.getOpenFileNames(self,'Добавить исходники',str(Path.home()),'Медиа (*.mp4 *.mov *.mkv *.avi *.webm *.m4v *.mts *.m2ts *.png *.jpg *.jpeg *.webp *.tiff);;Все файлы (*)')
        if paths:self.import_paths(paths)
    def dragEnterEvent(self,event):
        if event.mimeData().hasUrls() and not self.worker:event.acceptProposedAction()
    def dropEvent(self,event):
        paths=[u.toLocalFile() for u in event.mimeData().urls() if u.isLocalFile()]
        if paths:self.import_paths(paths)
    def import_paths(self,paths):
        existing={m.path for m in self.project.media};paths=[str(Path(p).resolve()) for p in paths if str(Path(p).resolve()) not in existing]
        if not paths:return
        def task(r,progress):
            media=[];errors=[]
            for i,p in enumerate(paths):
                progress(int(i/len(paths)*100),'Читаем исходник · '+Path(p).name)
                try:m=probe(p,r);m.thumb=thumbnail(m,app_dir()/'thumbs',r);media.append(m)
                except Cancelled:raise
                except Exception as e:errors.append(str(e))
            return media,errors
        def done(result):
            media,errors=result
            if media:
                self.before();self.project.media.extend(media)
                for m in media:
                    if not m.image:self.project.clips.append(Clip(m.id,0,m.duration,label=m.name))
                if not self.project_path and self.project.name=='Новый обзор':self.project.name=Path(media[0].name).stem
                self.changed();self.refresh()
            if errors:self.error('\n\n'.join(errors))
        self.start_job(task,done)
    def append_selected_media(self):
        item=self.media_list.currentItem()
        if not item:return
        m=next(m for m in self.project.media if m.id==item.data(Qt.ItemDataRole.UserRole));self.before();c=Clip(m.id,0,m.duration,label=m.name);self.project.clips.append(c);self.selected_id=c.id;self.changed();self.refresh_timeline();self.load_clip()
    def relink_media(self):
        item=self.media_list.currentItem()
        if not item:return
        m=next(m for m in self.project.media if m.id==item.data(Qt.ItemDataRole.UserRole));path,_=QFileDialog.getOpenFileName(self,'Найти '+m.name)
        if not path:return
        def done(new):
            if any(c.end>new.duration+.05 for c in self.project.clips if c.media_id==m.id):self.error('Этот файл короче используемых фрагментов. Выбери исходный файл.');return
            self.before();new.id=m.id;self.project.media[self.project.media.index(m)]=new;self.changed();self.refresh()
        def task(r,p):
            new=probe(path,r);new.thumb=thumbnail(new,app_dir()/'thumbs',r);return new
        self.start_job(task,done)
    def apply_clip(self):
        c=self.current_clip()
        if not c:return
        a=self.trim_in.value();b=self.trim_out.value();m=self.project.media_for(c)
        if not 0<=a<b<=m.duration+.02:self.error('Начало должно быть раньше конца, а конец — внутри исходника.');return
        self.before();c.start=a;c.end=min(b,m.duration);c.label=self.clip_name.text();c.speed=self.speed.value();c.volume=self.volume.value()/100;c.transition=self.transition.currentData();c.transition_seconds=self.transition_duration.value();c.crop_x=self.crop_x.value()/100;c.crop_y=self.crop_y.value()/100;self.project.validate();self.changed();self.refresh_timeline();self.load_source(m,a)
    def set_trim_cursor(self,is_start):
        c=self.current_clip()
        if not c or self.preview_mode:return
        if self.player.source().toLocalFile()!=self.project.media_for(c).path:return
        (self.trim_in if is_start else self.trim_out).setValue(self.player.position()/1000)
    def move_clip(self,delta):
        c=self.current_clip()
        if not c:return
        i=self.project.clips.index(c);j=i+delta
        if not 0<=j<len(self.project.clips):return
        self.before();self.project.clips[i],self.project.clips[j]=self.project.clips[j],self.project.clips[i];self.changed();self.refresh_timeline()
    def split_clip(self):
        c=self.current_clip()
        if not c or self.preview_mode:return
        if self.player.source().toLocalFile()!=self.project.media_for(c).path:return
        at=self.player.position()/1000
        if not c.start+.08<at<c.end-.08:self.statusBar().showMessage('Передвинь курсор внутрь выбранного фрагмента',5000);return
        self.before();right=copy.deepcopy(c);right.id=uid();right.start=at;right.transition='cut';offset=(at-c.start)/c.speed;c.end=at
        c.overlays=[copy.deepcopy(o) for o in c.overlays if o.start<c.duration]
        for o in c.overlays:o.end=min(o.end,c.duration)
        right.overlays=[o for o in right.overlays if o.end>offset]
        for o in right.overlays:o.start=max(0,o.start-offset);o.end-=offset
        self.project.clips.insert(self.project.clips.index(c)+1,right);self.changed();self.refresh_timeline();self.load_clip()
    def toggle_clip(self):
        c=self.current_clip()
        if not c:return
        self.before();c.enabled=not c.enabled;self.changed();self.refresh_timeline();self.load_clip()
    def edit_overlays(self):
        c=self.current_clip()
        if not c:return
        d=OverlayDialog(c,self.project.media,self)
        if d.exec()==QDialog.DialogCode.Accepted:self.before();c.overlays=d.overlays;self.changed();self.load_clip()
    def choose_lut(self):
        path,_=QFileDialog.getOpenFileName(self,'Цветовой LUT','','LUT (*.cube)')
        if path:self.lut.setText(path)
    def grade_from_fields(self):return Grade(**{k:w.value() for k,w in self.grade_fields.items()},vignette=self.vignette.isChecked(),lut=self.lut.text())
    def apply_preset(self):
        c=self.current_clip()
        if not c:return
        self.before();c.grade=copy.deepcopy(PRESETS[self.preset.currentText()]);self.changed();self.load_clip();self.show_grade_frame()
    def apply_grade(self):
        c=self.current_clip()
        if not c:return
        self.before();c.grade=self.grade_from_fields();self.changed();self.show_grade_frame()
    def grade_all(self):
        if not self.project.clips:return
        self.before();grade=self.grade_from_fields()
        for c in self.project.clips:c.grade=copy.deepcopy(grade)
        self.changed();self.load_clip();self.show_grade_frame()
    def show_grade_frame(self):
        c=self.current_clip()
        if not c:return
        clip=copy.deepcopy(c);m=self.project.media_for(c);self.player.pause()
        def done(path):self.display_still(path);self.viewer_title.setText('ОБРАБОТАННЫЙ КАДР · '+m.name)
        self.start_job(lambda r,p:color_preview(m,clip,app_dir()/'graded',r),done)
    def dimensions_changed(self):
        if self.loading:return
        n=self.resolution.currentData();a=self.aspect.currentData();self.width.setEnabled(a=='custom');self.height.setEnabled(a=='custom')
        if a=='landscape':self.width.setValue(round(n*16/9/2)*2);self.height.setValue(n)
        elif a=='portrait':self.width.setValue(n);self.height.setValue(round(n*16/9/2)*2)
        elif a=='square':self.width.setValue(n);self.height.setValue(n)
    def load_export(self):
        e=self.project.export
        for w,v in [(self.format,e.container),(self.codec,e.codec),(self.fps,e.fps),(self.fit,e.fit),(self.quality,e.quality)]:select_data(w,v)
        self.width.setValue(e.width);self.height.setValue(e.height);self.bitrate.setValue(e.bitrate_mbps);self.target_size.setValue(e.target_mb);self.music.setText(e.music);self.music_volume.setValue(e.music_volume*100);self.duck.setChecked(e.duck_music);self.normalize.setChecked(e.normalize_audio)
        a='landscape' if abs(e.width/e.height-16/9)<.01 else 'portrait' if abs(e.width/e.height-9/16)<.01 else 'square' if e.width==e.height else 'custom';select_data(self.aspect,a);select_data(self.resolution,min(e.width,e.height));self.width.setEnabled(a=='custom');self.height.setEnabled(a=='custom')
    def read_export(self):
        e=self.project.export;e.container=self.format.currentData();e.codec=self.codec.currentData();e.width=int(self.width.value());e.height=int(self.height.value());e.fps=float(self.fps.currentData());e.fit=self.fit.currentData();e.quality=self.quality.currentData();e.bitrate_mbps=self.bitrate.value();e.target_mb=self.target_size.value();e.normalize_audio=self.normalize.isChecked();e.music=self.music.text();e.music_volume=self.music_volume.value()/100;e.duck_music=self.duck.isChecked();self.project.validate()
    def choose_music(self):
        path,_=QFileDialog.getOpenFileName(self,'Фоновая музыка','','Аудио (*.mp3 *.m4a *.wav *.flac *.aac *.ogg);;Все файлы (*)')
        if path:self.music.setText(path)
    def render_preview(self,selected):
        if not self.project.active():return
        self.read_export();p=copy.deepcopy(self.project)
        if selected:
            c=self.current_clip()
            if not c:return
            c=copy.deepcopy(c);c.enabled=True;c.transition='cut';p.clips=[c]
        output=app_dir()/'preview.mp4';self.player.stop();self.player.setSource(QUrl())
        def done(path):
            self.preview_mode=True;self.viewer_title.setText('ГОТОВЫЙ МОНТАЖ · уменьшенное превью');self.player.setSource(QUrl.fromLocalFile(path));self.viewer.setCurrentWidget(self.video);self.player.play();self.play_button.setText('Ⅱ')
        self.start_job(lambda r,progress:Renderer(r,progress).render(p,output,preview=True),done,'Сборка превью',True)
    def export_video(self):
        if not self.project.active():self.error('Добавь хотя бы один фрагмент на монтажную ленту.');return
        try:self.read_export()
        except Exception as e:self.error(str(e));return
        ext=self.project.export.container;path,_=QFileDialog.getSaveFileName(self,'Сохранить видео',str(Path.home()/'Movies'/f'{self.project.name}.{ext}'),f'{ext.upper()} (*.{ext})')
        if not path:return
        if not path.lower().endswith('.'+ext):path+='.'+ext
        self.changed();p=copy.deepcopy(self.project)
        def done(output):
            self.status.setText('Видео сохранено: '+Path(output).name)
        self.start_job(lambda r,progress:Renderer(r,progress).render(p,path),done,'Экспорт видео',True)
    def settings_dialog(self):
        d=SettingsDialog(self)
        if d.exec()!=QDialog.DialogCode.Accepted:return
        self.api_key=d.key.text().strip();self.settings.setValue('budget',d.budget.value())
        try:
            import keyring
            if self.api_key:keyring.set_password('Oldy Cut','OpenAI',self.api_key)
            else:
                try:keyring.delete_password('Oldy Cut','OpenAI')
                except keyring.errors.PasswordDeleteError:pass
            self.statusBar().showMessage('Настройки сохранены',5000)
        except Exception:self.statusBar().showMessage('Связка ключей недоступна. Ключ действует только до закрытия приложения.',10000)
    def ai_analyze(self):
        if not self.api_key:self.settings_dialog()
        if not self.api_key:return
        if not any(not m.image for m in self.project.media):self.error('Сначала добавь видеоисходники.');return
        self.project.prompt=self.prompt.toPlainText().strip();p=copy.deepcopy(self.project);check=self.check_audio.isChecked();key=self.api_key;budget=self.settings.value('budget',10,type=float)
        def task(r,progress):return Planner(API(key,r,budget),progress).analyze(p,check)
        self.start_job(task,self.apply_ai,'Монтаж по заданию',True)
    def ai_refine(self):
        instruction=self.refine.toPlainText().strip()
        if not instruction or not self.project.clips:return
        if not self.api_key:self.settings_dialog()
        if not self.api_key:return
        p=copy.deepcopy(self.project);key=self.api_key;budget=self.settings.value('budget',10,type=float)
        self.start_job(lambda r,progress:Planner(API(key,r,budget),progress).refine(p,instruction),self.apply_ai,'Правки монтажа с GPT‑6',True)
    def apply_ai(self,p):self.before();self.project=p;self.changed();self.refresh();self.status.setText('Монтаж собран. Проверь вырезки и заметки перед экспортом.')
    def remove_silence(self):
        if not self.project.active():return
        p=copy.deepcopy(self.project)
        def task(r,progress):
            ranges={};media=[m for m in p.media if not m.image]
            for i,m in enumerate(media):progress(int(i/max(1,len(media))*90),'Поиск тишины · '+m.name);ranges[m.id]=silence_ranges(m,r)
            clips=[]
            for c in p.clips:
                if not c.enabled:clips.append(c);continue
                kept=subtract_ranges(c.start,c.end,ranges.get(c.media_id,[]));pos=c.start
                for a,b in kept:
                    if a>pos+.08:
                        removed=copy.deepcopy(c);removed.id=uid();removed.start=pos;removed.end=a;removed.enabled=False;removed.reason='Тишина · можно вернуть';removed.overlays=[];clips.append(removed)
                    part=copy.deepcopy(c);part.id=uid();part.start=a;part.end=b;offset=(a-c.start)/c.speed;part.overlays=[copy.deepcopy(o) for o in c.overlays if o.end>offset and o.start<offset+part.duration]
                    for o in part.overlays:o.start=max(0,o.start-offset);o.end=min(part.duration,o.end-offset)
                    if a>c.start+.08:part.transition='cut'
                    clips.append(part);pos=b
                if c.end>pos+.08:
                    removed=copy.deepcopy(c);removed.id=uid();removed.start=pos;removed.enabled=False;removed.reason='Тишина · можно вернуть';removed.overlays=[];clips.append(removed)
            p.clips=clips;p.validate();return p
        self.start_job(task,self.apply_ai)
    def show_job(self):
        if self.job_dialog:self.job_dialog.show();self.job_dialog.raise_();self.job_dialog.activateWindow()
        else:QMessageBox.information(self,'Ход работы','После запуска операции здесь появятся этапы, время и журнал.')
    def start_job(self,task,done,title='Обработка',auto_show=False):
        if self.worker:return
        if self.job_dialog:self.job_dialog.close();self.job_dialog.deleteLater()
        self.handling_result=False;self.worker=Worker(task,title);self.job_dialog=JobDialog(self,title,self.worker.journal.path)
        self.job_dialog.cancelRequested.connect(self.cancel_job);self.job_dialog.summaryChanged.connect(self.status.setText)
        self.worker.event.connect(self.job_dialog.receive);self.worker.event.connect(self.reflect_job_progress)
        self.busy(True);self.status.setText('Подготовка…');self.progress.setRange(0,0)
        self.worker.progress.connect(lambda n,s:self.job_dialog.context.setText(s))
        self.worker.succeeded.connect(lambda result:self.job_succeeded(result,done,title))
        self.worker.failed.connect(self.job_failed);self.worker.cancelled.connect(self.job_cancelled)
        self.worker.finished.connect(self.finish_job);self.worker.start()
        if auto_show:self.show_job()
    def reflect_job_progress(self,event):
        bar=self.job_dialog.bar;self.progress.setRange(bar.minimum(),bar.maximum());self.progress.setValue(bar.value())
    def job_succeeded(self,result,done,title):
        self.handling_result=True
        try:done(result)
        except Exception as exc:
            self.worker.report({'type':'log','message':traceback.format_exc()});self.job_failed(exc);return
        message='Готово. '+('Монтажный план применён. Дальше — просмотр и «Экспорт видео».' if isinstance(result,Project) else 'Видео сохранено.' if title=='Экспорт видео' else 'Результат готов к просмотру.')
        self.worker.report({'type':'log','message':message});self.job_dialog.finish(True,message);self.handling_result=False
    def job_failed(self,error):
        self.handling_result=True
        self.job_dialog.finish(False,'Операция не завершена. Причина — в журнале.');self.show_job()
        if isinstance(error,UnconfirmedRequest):
            box=QMessageBox(self);box.setWindowTitle('Запрос OpenAI');box.setText(str(error))
            reset=box.addButton('Разрешить новую попытку',QMessageBox.ButtonRole.ActionRole);box.addButton('Оставить как есть',QMessageBox.ButtonRole.RejectRole);box.exec()
            if box.clickedButton()==reset:
                error.path.unlink(missing_ok=True);self.worker.report({'type':'log','message':'Пользователь разрешил новый запрос вместо неподтверждённого. Повторный монтаж ещё не запущен.'})
        else:QMessageBox.warning(self,'Oldy Cut',redact(error,[self.api_key])[:3500]+'\n\nНажми «Сохранить журнал…» в окне хода работы для диагностики.')
        self.handling_result=False
    def job_cancelled(self):self.job_dialog.finish(False,'Остановлено. Исходники и выполненный анализ сохранены.')
    def finish_job(self):
        if getattr(self,'handling_result',False):QTimer.singleShot(100,self.finish_job);return
        worker=self.worker;self.worker=None;self.busy(False)
        if worker:worker.deleteLater()
    def busy(self,on):
        self.splitter.setEnabled(not on);self.export_button.setEnabled(not on);self.menuBar().setEnabled(not on);self.progress.setVisible(on);self.cancel_button.setVisible(on)
        for b in self.centralWidget().findChildren(QPushButton,options=Qt.FindChildOption.FindDirectChildrenOnly):b.setEnabled(not on)
        self.cancel_button.setEnabled(True);self.job_button.setEnabled(True)
    def cancel_job(self):
        if self.worker:
            self.worker.runner.cancel();self.status.setText('Останавливаем… ожидаем завершения текущего сетевого обмена.');self.cancel_button.setEnabled(False)
            if self.job_dialog:self.job_dialog.cancel_button.setEnabled(False)
    def error(self,message):self.status.setText('Операция не завершена');QMessageBox.warning(self,'Oldy Cut',redact(message,[self.api_key])[:4000])
    def undo(self,redo=False):
        if self.worker:return
        self.project=self.history.redo(self.project) if redo else self.history.undo(self.project);self.changed();self.refresh()
    def save_project(self,save_as=False):
        if self.worker:return
        self.read_export();path=self.project_path
        if save_as or not path:path,_=QFileDialog.getSaveFileName(self,'Сохранить проект',str(Path.home()/'Documents'/f'{self.project.name}.oldycut'),'Проект Oldy Cut (*.oldycut)')
        if not path:return
        if not path.endswith('.oldycut'):path+='.oldycut'
        try:self.project.save(path);self.project_path=path;self.dirty=False;self.refresh();self.statusBar().showMessage('Проект сохранён',5000)
        except Exception as e:self.error(str(e))
    def new_project(self):
        if self.worker:return
        # Every replaced project gets its own recoverable local snapshot.
        if self.project.media:self.project.save(app_dir()/'projects'/(self.project.id+'.oldycut'))
        self.player.stop();self.project=Project();self.project_path='';self.selected_id=None;self.history=History();self.dirty=False;self.refresh();self.display_still('','Добавь видео, чтобы начать')
    def open_project(self):
        path,_=QFileDialog.getOpenFileName(self,'Открыть проект',str(Path.home()/'Documents'),'Проект Oldy Cut (*.oldycut)')
        if not path:return
        try:
            p=Project.load(path)
            if self.project.media:self.project.save(app_dir()/'projects'/(self.project.id+'.oldycut'))
            self.player.stop();self.project=p;self.project_path=path;self.history=History();self.selected_id=None;self.dirty=False;self.refresh()
        except Exception as e:self.error(str(e))
    def help(self):
        QMessageBox.information(self,'Как работать','1. Добавь видео. Фотографии и мемы останутся в исходниках — их можно вставить поверх видео.\n\n2. Напиши задание в разделе ИИ и нажми «Собрать с GPT‑6», либо монтируй вручную.\n\n3. Выбери фрагмент. Измени начало/конец, переход, скорость, цвет или надписи. Исключённые сцены доступны через «Вырезки». Cmd+Z отменяет правку.\n\n4. Исходник играет без эффектов. «Превью фрагмента» и «Превью монтажа» собирают обработанное видео.\n\n5. В разделе «Вывод» выбери MP4, размер кадра и качество. Нажми «Экспорт видео».\n\nСохраняй проект .oldycut. Он содержит монтаж, а исходные файлы остаются на своих местах.')
    def closeEvent(self,event):
        if self.worker:
            self.cancel_job();self.status.setText('Дождись остановки операции, затем закрой приложение.');event.ignore();return
        self.player.stop()
        try:self.read_export();self.project.save(app_dir()/'last.oldycut')
        except Exception:pass
        event.accept()

def run():
    app=QApplication(sys.argv);app.setApplicationName('Oldy Cut');app.setOrganizationName('Oldy');app.setStyle('Fusion');app.setStyleSheet(STYLE)
    icon=resources()/'oldycut.svg'
    if icon.exists():app.setWindowIcon(QIcon(str(icon)))
    window=MainWindow();window.show()
    if len(sys.argv)>1 and sys.argv[1].endswith('.oldycut'):
        try:window.project=Project.load(sys.argv[1]);window.project_path=sys.argv[1];window.refresh()
        except Exception as e:window.error(str(e))
    return app.exec()
