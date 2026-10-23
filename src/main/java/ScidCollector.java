import com.dukascopy.api.*;
import com.dukascopy.api.system.ClientFactory;
import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.ISystemListener;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class ScidCollector extends JFrame {

    private JComboBox<String> accountTypeCombo;
    private JTextField userField, passField, symbolsField, m1DaysField, tickDaysField, saveDirField;
    private JTextArea logArea;
    private JButton startButton;
    private Properties props = new Properties();
    private final String CONFIG_FILE = "collector.properties";
    private IClient client;
    private boolean isRunning = false;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ScidCollector().setVisible(true));
    }

    public ScidCollector() {
        setTitle("Dukascopy SCID Collector (Sierra Chart)");
        setSize(650, 550);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        loadConfig();

        JPanel panel = new JPanel(new GridLayout(7, 2, 5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        panel.add(new JLabel("Тип счета:"));
        accountTypeCombo = new JComboBox<>(new String[]{"DEMO", "LIVE"});
        accountTypeCombo.setSelectedItem(props.getProperty("account_type", "DEMO"));
        panel.add(accountTypeCombo);

        panel.add(new JLabel("Логин:"));
        userField = new JTextField(props.getProperty("username", ""));
        addContextMenu(userField);
        panel.add(userField);

        panel.add(new JLabel("Пароль:"));
        passField = new JPasswordField(props.getProperty("password", ""));
        addContextMenu(passField);
        panel.add(passField);

        panel.add(new JLabel("Инструменты (Тикер=Файл):"));
        symbolsField = new JTextField(props.getProperty("symbols", "USATECHIDXUSD=NAS100, EURUSD=EURUSD, XAUUSD=XAUUSD"));
        addContextMenu(symbolsField);
        panel.add(symbolsField);

        panel.add(new JLabel("Глубина истории минут (дней):"));
        m1DaysField = new JTextField(props.getProperty("m1days", "9999"));
        addContextMenu(m1DaysField);
        panel.add(m1DaysField);

        panel.add(new JLabel("Глубина истории тиков (дней):"));
        tickDaysField = new JTextField(props.getProperty("tickdays", "55"));
        addContextMenu(tickDaysField);
        panel.add(tickDaysField);

        panel.add(new JLabel("Папка сохранения:"));
        saveDirField = new JTextField(props.getProperty("savedir", "C:\\SierraChart\\Data"));
        addContextMenu(saveDirField);
        panel.add(saveDirField);

        add(panel, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setEditable(false);
        addContextMenu(logArea); 
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        startButton = new JButton("СТАРТ");
        startButton.setFont(new Font("Arial", Font.BOLD, 16));
        startButton.setBackground(new Color(46, 204, 113));
        startButton.addActionListener(e -> toggleStart());
        add(startButton, BorderLayout.SOUTH);
    }

    private void addContextMenu(JTextComponent textComponent) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem copy = new JMenuItem("Копировать");
        JMenuItem paste = new JMenuItem("Вставить");
        JMenuItem cut = new JMenuItem("Вырезать");
        JMenuItem selectAll = new JMenuItem("Выделить всё");

        copy.addActionListener(e -> textComponent.copy());
        paste.addActionListener(e -> textComponent.paste());
        cut.addActionListener(e -> textComponent.cut());
        selectAll.addActionListener(e -> textComponent.selectAll());

        popup.add(copy);
        popup.add(paste);
        popup.add(cut);
        popup.addSeparator();
        popup.add(selectAll);

        textComponent.setComponentPopupMenu(popup);
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void saveConfig() {
        try {
            props.setProperty("account_type", (String) accountTypeCombo.getSelectedItem());
            props.setProperty("username", userField.getText().trim());
            props.setProperty("password", passField.getText().trim());
            props.setProperty("symbols", symbolsField.getText().trim());
            props.setProperty("m1days", m1DaysField.getText().trim());
            props.setProperty("tickdays", tickDaysField.getText().trim());
            props.setProperty("savedir", saveDirField.getText().trim());
            props.store(new FileOutputStream(CONFIG_FILE), "Dukascopy Config");
        } catch (Exception ignored) {}
    }

    private void loadConfig() {
        try {
            File f = new File(CONFIG_FILE);
            if (f.exists()) props.load(new FileInputStream(f));
        } catch (Exception ignored) {}
    }

    private void toggleStart() {
        if (!isRunning) {
            saveConfig();
            startButton.setText("РАБОТАЕТ (Закройте окно для остановки)");
            startButton.setEnabled(false);
            accountTypeCombo.setEnabled(false);
            userField.setEnabled(false);
            passField.setEnabled(false);
            isRunning = true;
            new Thread(this::startDukascopy).start();
        }
    }

    private void startDukascopy() {
        try {
            client = ClientFactory.getDefaultInstance();
            client.setSystemListener(new ISystemListener() {
                @Override public void onStart(long pId) { log(">>> Процесс сбора запущен."); }
                @Override public void onStop(long pId) { log(">>> Процесс остановлен."); }
                @Override public void onConnect() { log(">>> УСПЕШНОЕ ПОДКЛЮЧЕНИЕ к серверам Dukascopy."); }
                
                @Override public void onDisconnect() {
                    log("!!! ОБРЫВ СВЯЗИ. Автоматическое переподключение через 10 секунд...");
                    try { Thread.sleep(10000); } catch (Exception ignored) {}
                    if (isRunning) startDukascopy(); 
                }
            });

            log("Подключение к серверам " + accountTypeCombo.getSelectedItem() + "...");
            System.setProperty("http.agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
            
            String jnlpUrl = accountTypeCombo.getSelectedItem().equals("LIVE") 
                ? "http://platform.dukascopy.com/live_3/jforex_3.jnlp" 
                : "http://platform.dukascopy.com/demo_3/jforex_3.jnlp";

            client.connect(jnlpUrl, userField.getText(), passField.getText());

            int attempts = 30;
            while (!client.isConnected() && attempts > 0) {
                Thread.sleep(1000);
                attempts--;
            }

            if (!client.isConnected()) {
                log("ОШИБКА: Не удалось авторизоваться. Проверьте логин/пароль.");
                isRunning = false;
                SwingUtilities.invokeLater(() -> {
                    startButton.setText("СТАРТ");
                    startButton.setEnabled(true);
                    accountTypeCombo.setEnabled(true);
                    userField.setEnabled(true);
                    passField.setEnabled(true);
                });
                return;
            }

            client.startStrategy(new ScidStrategy());

        } catch (Exception e) {
            log("Критическая ошибка: " + e.getMessage());
        }
    }

    class ScidStrategy implements IStrategy {
        private final Map<Instrument, ScidWriter> writers = new HashMap<>();
        private boolean realtimeReady = false;

        @Override
        public void onStart(IContext context) throws JFException {
            String[] symbolPairs = symbolsField.getText().split(",");
            Set<Instrument> instruments = new HashSet<>();
            Map<Instrument, String> fileMap = new HashMap<>();

            for (String pair : symbolPairs) {
                pair = pair.trim();
                if (pair.isEmpty()) continue;
                
                String instName;
                String fileName;

                if (pair.contains("=")) {
                    String[] parts = pair.split("=");
                    instName = parts[0].trim().toUpperCase();
                    fileName = parts[1].trim();
                } else {
                    instName = pair.toUpperCase();
                    fileName = instName.replace("/", "");
                }

                if (!fileName.toLowerCase().endsWith(".scid")) {
                    fileName += ".scid";
                }

                try {
                    Instrument inst = Instrument.valueOf(instName);
                    instruments.add(inst);
                    fileMap.put(inst, fileName);
                } catch (Exception e) {
                    log("ОШИБКА: Инструмент '" + instName + "' не распознан API Dukascopy.");
                }
            }
            context.setSubscribedInstruments(instruments);

            int m1Days = Integer.parseInt(m1DaysField.getText().trim());
            int tDays = Integer.parseInt(tickDaysField.getText().trim());

            for (Instrument inst : instruments) {
                String fileName = fileMap.get(inst);
                String fullPath = saveDirField.getText().trim() + File.separator + fileName;
                log("Инициализация файла: " + fullPath);

                try {
                    ScidWriter writer = new ScidWriter(fullPath);
                    writers.put(inst, writer);
                    long lastTime = writer.getLastTimeMs();

                    long serverNow = System.currentTimeMillis();
                    try {
                        ITick lastServerTick = context.getHistory().getLastTick(inst);
                        if (lastServerTick != null) {
                            serverNow = lastServerTick.getTime();
                        }
                    } catch (Exception ignored) {}

                    if (lastTime == 0) {
                        log("[" + inst + "] Скачивание минутного архива...");
                        loadBars(context.getHistory(), inst, writer, serverNow - (m1Days * 86400000L), serverNow - (tDays * 86400000L));
                        log("[" + inst + "] Скачивание тикового архива...");
                        loadTicks(context.getHistory(), inst, writer, serverNow - (tDays * 86400000L), serverNow);
                    } else if (lastTime < serverNow - 5000) {
                        log("[" + inst + "] Докачка пропущенных тиков (Backfill)...");
                        loadTicks(context.getHistory(), inst, writer, lastTime, serverNow);
                    }
                } catch (Exception e) {
                    log("Ошибка с " + inst + ". Загрузка прервана: " + e.getMessage());
                }
            }
            realtimeReady = true;
            log(">>> ЗАГРУЗКА ЗАВЕРШЕНА. ЗАПУЩЕН СБОР В РЕАЛЬНОМ ВРЕМЕНИ (Live).");
        }

        @Override
        public void onTick(Instrument instrument, ITick tick) throws JFException {
            if (!realtimeReady) return;
            ScidWriter w = writers.get(instrument);
            if (w != null) {
                try { 
                    w.writeTick(tick.getTime(), tick.getBid(), tick.getAsk(), tick.getBidVolume(), tick.getAskVolume()); 
                } catch (Exception ignored) {}
            }
        }

        private void loadBars(IHistory hist, Instrument inst, ScidWriter writer, long from, long to) throws Exception {
            long step = 30L * 86400000L; 
            from = from - (from % 60000);
            to = to - (to % 60000);
            long sessionStartMs = writer.getLastTimeMs(); 
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

            if (sessionStartMs == 0) {
                log("[" + inst + "] Поиск фактического начала минутного архива...");
                boolean found = false;
                while (from < to) {
                    long pEnd = Math.min(from + step, to);
                    try {
                        List<IBar> probe = hist.getBars(inst, Period.ONE_MIN, OfferSide.BID, from, pEnd);
                        if (probe != null && !probe.isEmpty()) {
                            from = probe.get(0).getTime();
                            from = from - (from % 60000);
                            found = true;
                            log("[" + inst + "] Найдена история M1 начиная с: " + sdf.format(new Date(from)));
                            break;
                        }
                    } catch (Exception ignored) {}
                    from += step;
                }
                if (!found) return;
            }

            for (long cStart = from; cStart < to; cStart += step) {
                long cEnd = Math.min(cStart + step, to);
                cEnd = cEnd - (cEnd % 60000);
                
                log("[" + inst + "] M1: загрузка " + sdf.format(new Date(cStart)) + " ...");
                try {
                    List<IBar> bars = hist.getBars(inst, Period.ONE_MIN, OfferSide.BID, cStart, cEnd);
                    if (bars != null && !bars.isEmpty()) {
                        int count = 0;
                        for (IBar b : bars) {
                            if (b.getVolume() > 0 && b.getTime() > sessionStartMs) {
                                writer.writeBar(b.getTime(), b.getOpen(), b.getHigh(), b.getLow(), b.getClose(), b.getVolume());
                                count++;
                            }
                        }
                        log("[" + inst + "]   -> Сохранено свечей: " + count);
                    } else {
                        log("[" + inst + "]   -> Сервер вернул 0 свечей (пусто)");
                    }
                } catch (Exception e) {
                    log("[" + inst + "] Пропуск блока M1 (ошибка: " + e.getMessage() + ")");
                }
            }
        }

        private void loadTicks(IHistory hist, Instrument inst, ScidWriter writer, long from, long to) throws Exception {
            long safeTo = to - 120000;
            if (from >= safeTo) return;

            long step = 1800000L; 
            long sessionStartMs = writer.getLastTimeMs(); 
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            long cStart = from;
            while (cStart < safeTo) {
                long cEnd = Math.min(cStart + step, safeTo);
                log("[" + inst + "] ТИКИ: " + sdf.format(new Date(cStart)) + " - " + sdf.format(new Date(cEnd)));

                try {
                    List<ITick> ticks = hist.getTicks(inst, cStart, cEnd);
                    if (ticks != null && !ticks.isEmpty()) {
                        int count = 0;
                        for (ITick t : ticks) {
                            if (t.getTime() > sessionStartMs) {
                                writer.writeTick(t.getTime(), t.getBid(), t.getAsk(), t.getBidVolume(), t.getAskVolume());
                                count++;
                            }
                        }
                        log("[" + inst + "]   -> Сохранено тиков: " + count);
                    } else {
                        log("[" + inst + "]   -> Сервер вернул 0 тиков (пусто)");
                    }
                    
                    cStart = cEnd;
                    step = 1800000L; 
                    Thread.sleep(50); 
                } catch (Exception e) {
                    String err = e.getMessage() != null ? e.getMessage() : "Timeout";
                    log("[" + inst + "] Ошибка API: " + err);

                    step = step / 2; 
                    if (step >= 60000) {
                        log("[" + inst + "] Уменьшаем блок до " + (step / 60000) + " мин. Пробуем снова...");
                        Thread.sleep(2000);
                    } else {
                        log("[" + inst + "] Критический отказ сервера. Пропуск 1 минуты.");
                        cStart += 60000; 
                        step = 1800000L; 
                    }
                }
            }
        }

        @Override public void onStop() throws JFException { writers.values().forEach(ScidWriter::close); }
        @Override public void onBar(Instrument i, Period p, IBar a, IBar b) {}
        @Override public void onMessage(IMessage message) {}
        @Override public void onAccount(IAccount account) {}
    }

    class ScidWriter {
        private final FileChannel channel;
        private final RandomAccessFile raf;
        private long lastTimeMs = 0;
        private double lastExcelDate = 0.0;
        private double lastMid = 0;

        public ScidWriter(String filePath) throws Exception {
            File file = new File(filePath);
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            boolean exists = file.exists() && file.length() >= 56;
            this.raf = new RandomAccessFile(file, "rw");
            this.channel = raf.getChannel();

            if (!exists) {
                ByteBuffer header = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
                header.put("SCID".getBytes()).put((byte)'8').put((byte)0).put((byte)0).put((byte)0);
                header.putInt(40).putInt(1);
                while (header.hasRemaining()) header.put((byte)0);
                header.flip();
                channel.write(header);
            } else {
                long size = channel.size();
                long remainder = (size - 56) % 40;
                if (remainder != 0) {
                    channel.truncate(size - remainder);
                    size -= remainder;
                }
                if (size >= 96) {
                    ByteBuffer buf = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
                    channel.position(size - 40);
                    channel.read(buf);
                    buf.flip();
                    this.lastExcelDate = buf.getDouble();
                    this.lastTimeMs = Math.round((this.lastExcelDate * 86400000.0) - 2209161600000.0);
                    channel.position(size);
                }
            }
        }

        public synchronized void writeBar(long timeMs, double open, double high, double low, double close, double dukasVolume) throws Exception {
            double excelDate = (timeMs + 2209161600000.0) / 86400000.0;
            if (excelDate <= this.lastExcelDate) {
                excelDate = this.lastExcelDate + (1.0 / 86400000.0);
            }
            this.lastExcelDate = excelDate;
            this.lastTimeMs = timeMs;

            int vol = (int)(dukasVolume * 1000000.0);
            if (vol <= 0) vol = 1;

            ByteBuffer buf = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
            buf.putDouble(excelDate).putFloat((float)open).putFloat((float)high).putFloat((float)low).putFloat((float)close).putInt(vol).putInt(0).putInt(0).putInt(0);
            buf.flip();
            channel.write(buf);
        }

        public synchronized void writeTick(long timeMs, double bid, double ask, double dukasBidVol, double dukasAskVol) throws Exception {
            if (bid <= 0 || ask <= 0) return;
            double mid = (bid + ask) * 0.5;
            double closePrice = mid >= lastMid ? ask : bid;
            
            int rawBidVol = (int)(dukasBidVol * 1000000.0);
            int rawAskVol = (int)(dukasAskVol * 1000000.0);

            int askVol = 0;
            int bidVol = 0;
            if (mid >= lastMid) {
                askVol = rawAskVol > 0 ? rawAskVol : 1; 
            } else {
                bidVol = rawBidVol > 0 ? rawBidVol : 1;
            }
            lastMid = mid;

            int totalVol = askVol + bidVol;
            double excelDate = (timeMs + 2209161600000.0) / 86400000.0;
            
            if (excelDate <= this.lastExcelDate) {
                excelDate = this.lastExcelDate + (1.0 / 86400000000.0); 
            }
            this.lastExcelDate = excelDate;
            this.lastTimeMs = timeMs;

            ByteBuffer buf = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
            buf.putDouble(excelDate).putFloat(0.0f).putFloat((float)ask).putFloat((float)bid).putFloat((float)closePrice).putInt(1).putInt(totalVol).putInt(bidVol).putInt(askVol);
            buf.flip();
            channel.write(buf);
        }

        public long getLastTimeMs() { return lastTimeMs; }
        public void close() { try { channel.close(); raf.close(); } catch (Exception ignored) {} }
    }
}
