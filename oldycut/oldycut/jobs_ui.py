"""Visible job status with real FFmpeg progress and a readable local journal."""
from __future__ import annotations
import shutil, time
from pathlib import Path
from PySide6.QtCore import QTimer, QUrl, Signal
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import QDialog,QVBoxLayout,QHBoxLayout,QLabel,QPushButton,QProgressBar,QListWidget,QPlainTextEdit,QFileDialog,QMessageBox
from .diagnostics import remaining_seconds


def clock(seconds):
    s=max(0,int(seconds));h,s=divmod(s,3600);m,s=divmod(s,60)
    return f'{h}:{m:02}:{s:02}' if h else f'{m}:{s:02}'


class JobDialog(QDialog):
    cancelRequested=Signal();summaryChanged=Signal(str)
    def __init__(self,parent,title,log_path):
        super().__init__(parent);self.setWindowTitle('Ход работы · Oldy Cut');self.resize(740,620)
        self.log_path=Path(log_path);self.started=time.monotonic();self.running=True;self.kind='local';self.current='Подготовка';self.eta=None;self.phase_eta=None;self.contact=None
        self.items={};self.completed=set();self.work={};self.measured=0;self.phase_seconds=0
        layout=QVBoxLayout(self);layout.setContentsMargins(22,20,22,18);layout.setSpacing(12)
        self.heading=QLabel(title);self.heading.setProperty('heading',True);layout.addWidget(self.heading)
        self.context=QLabel('Подготовка…');self.context.setWordWrap(True);layout.addWidget(self.context)
        self.stage=QLabel('Подготовка');self.stage.setWordWrap(True);layout.addWidget(self.stage)
        self.bar=QProgressBar();self.bar.setRange(0,0);self.bar.setTextVisible(False);layout.addWidget(self.bar)
        self.detail=QLabel('');self.detail.setWordWrap(True);self.detail.setProperty('muted',True);layout.addWidget(self.detail)
        self.timing=QLabel('Прошло 0:00 · Осталось: рассчитываем');self.timing.setWordWrap(True);layout.addWidget(self.timing)
        self.connection=QLabel('');self.connection.setProperty('muted',True);layout.addWidget(self.connection)
        self.roadmap=QLabel('');self.roadmap.setWordWrap(True);self.roadmap.setProperty('muted',True);layout.addWidget(self.roadmap)
        self.steps=QListWidget();self.steps.setMaximumHeight(145);layout.addWidget(self.steps)
        self.journal=QPlainTextEdit();self.journal.setReadOnly(True);self.journal.setMaximumBlockCount(4000);self.journal.setPlaceholderText('Здесь появятся выполненные действия и ответы сервера.');layout.addWidget(self.journal,1)
        buttons=QHBoxLayout();self.save_button=QPushButton('Сохранить журнал…');self.save_button.clicked.connect(self.save_log);buttons.addWidget(self.save_button)
        self.folder_button=QPushButton('Папка журналов');self.folder_button.clicked.connect(lambda:QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.log_path.parent))));buttons.addWidget(self.folder_button)
        buttons.addStretch();self.cancel_button=QPushButton('Отменить');self.cancel_button.clicked.connect(self.cancel);buttons.addWidget(self.cancel_button)
        self.hide_button=QPushButton('Свернуть');self.hide_button.clicked.connect(self.hide);buttons.addWidget(self.hide_button);layout.addLayout(buttons)
        self.timer=QTimer(self);self.timer.setInterval(500);self.timer.timeout.connect(self.tick);self.timer.start();self.tick()
    def cancel(self):
        self.cancel_button.setEnabled(False);self.stage.setText('Останавливаем операцию…');self.cancelRequested.emit()
    def tick(self):
        elapsed=clock(time.monotonic()-self.started)
        if self.running:
            if self.kind=='cloud':remaining='Осталось: OpenAI не сообщает время'
            elif self.eta is not None:remaining='Осталось примерно '+clock(self.eta)
            elif self.phase_eta is not None:remaining='До конца этапа примерно '+clock(self.phase_eta)+' · далее ещё этапы'
            else:remaining='Осталось: рассчитываем по скорости обработки'
            self.timing.setText('Прошло '+elapsed+' · '+remaining)
            self.summaryChanged.emit(self.current+' · прошло '+elapsed)
            if self.kind=='cloud' and self.contact is not None:self.connection.setText('Последний ответ OpenAI '+clock(time.monotonic()-self.contact)+' назад')
            else:self.connection.setText('')
    def add_step(self,name):
        if name not in self.items:
            self.steps.addItem('○ '+name);self.items[name]=self.steps.item(self.steps.count()-1)
        return self.items[name]
    def receive(self,event):
        kind=event['type']
        if kind=='line':self.journal.appendPlainText(event['message']);return
        if kind=='context':self.context.setText(event['message'])
        elif kind=='roadmap':self.roadmap.setText('Этапы: '+' → '.join(event['names']))
        elif kind=='plan':
            for name in event['names']:self.add_step(name)
            self.work=dict(zip(event['names'],event.get('seconds',[])))
        elif kind=='phase':
            self.current=event['name'];self.kind=event['kind'];self.phase_eta=None;self.eta=None;self.phase_seconds=0
            self.add_step(self.current).setText('● '+self.current);self.completed.discard(self.current)
            self.stage.setText(('OpenAI · ' if self.kind=='cloud' else 'На Mac · ')+self.current)
            self.bar.setRange(0,0);self.detail.setText('Ожидаем ответ сервера. Загрузка процессора Mac может быть низкой.' if self.kind=='cloud' else 'Ожидаем первые обработанные кадры…')
        elif kind=='complete':
            name=event['name'];self.add_step(name).setText('✓ '+name);self.completed.add(name)
            if self.work:self.roadmap.setText(f'Завершено {len(self.completed & self.work.keys())} из {len(self.work)} этапов')
        elif kind=='media':
            seconds=event['seconds'];total=event['total'];self.phase_seconds=seconds
            self.phase_eta=remaining_seconds(seconds,total,event['elapsed'])
            if self.work:
                processed=sum(self.work[x] for x in self.completed if x in self.work)+seconds
                all_seconds=sum(self.work.values());self.eta=remaining_seconds(processed,all_seconds,time.monotonic()-self.started)
                fraction=processed/max(.01,all_seconds)
            else:fraction=seconds/max(.01,total)
            self.bar.setRange(0,100);self.bar.setValue(min(99,int(fraction*100)))
            self.detail.setText(f'Обработано {clock(seconds)} из {clock(total)} на этапе · {event.get("fps", "—")} кадр/с · скорость {event.get("speed", "—")} · {self.bar.value()}%'+(' всего' if self.work else ' этапа'))
        elif kind=='cloud':self.kind='cloud';self.bar.setRange(0,0);self.detail.setText(event['message'])
        elif kind=='contact':self.contact=time.monotonic()
        self.tick()
    def finish(self,success,message):
        self.running=False;self.timer.stop();self.cancel_button.hide();self.hide_button.setText('Закрыть');self.bar.setRange(0,100)
        if success:self.bar.setValue(100)
        else:
            if self.current in self.items:self.items[self.current].setText('! '+self.current)
        self.stage.setText(message);self.detail.setText('Журнал сохранён автоматически. Его можно открыть или отправить для диагностики.')
        self.timing.setText('Всего прошло '+clock(time.monotonic()-self.started));self.summaryChanged.emit(message)
    def save_log(self):
        dest,_=QFileDialog.getSaveFileName(self,'Сохранить журнал',str(Path.home()/'Desktop'/self.log_path.name),'Журнал (*.log)')
        if dest:
            try:
                if Path(dest).resolve()!=self.log_path.resolve():shutil.copyfile(self.log_path,dest)
            except OSError as exc:QMessageBox.warning(self,'Журнал',str(exc))
