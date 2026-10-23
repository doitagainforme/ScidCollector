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
                ? "https://platform.dukascopy.com/live_3/jforex_3.jnlp" 
                : "https://platform.dukascopy.com/demo_3/jforex_3.jnlp";

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

    // =========================================================================================
    // СТРАТЕГИЯ СБОРА И ДОКАЧКИ
    // =========================================================================================
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
            long now = System.currentTimeMillis();
            now = now - (now % 60000);

            for (Instrument inst : instruments) {
                String fileName = fileMap.get(inst);
                String fullPath = saveDirField.getText().trim() + File.separator + fileName;
                log("Инициализация файла: " + fullPath);

                try {
                    ScidWriter writer = new ScidWriter(fullPath);
                    writers.put(inst, writer);
                    long lastTime = writer.getLastTimeMs();

                    if (lastTime == 0) {
                        log("[" + inst + "] Скачивание минутного архива...");
                        loadBars(context.getHistory(), inst, writer, now - (m1Days * 86400000L), now - (tDays * 86400000L));
                        log("[" + inst + "] Скачивание тикового архива...");
                        loadTicks(context.getHistory(), inst, writer, now - (tDays * 86400000L), now);
                    } else if (lastTime < now - 5000) {
                        log("[" + inst + "] Докачка пропущенных тиков (Backfill)...");
                        loadTicks(context.getHistory(), inst, writer, lastTime, now);
                    }
                } catch (Exception e) {
                    log("Ошибка с " + inst + ": " + e.getMessage());
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
                log("[" + inst + "] Поиск фактического начала минутного архива (пропуск пустых лет)...");
                boolean found = false;
                while (from < to) {
                    long pEnd = Math.min(from + step, to);
                    List<IBar> probe = hist.getBars(inst, Period.ONE_MIN, OfferSide.BID, from, pEnd);
                    if (probe != null && !probe.isEmpty()) {
                        from = probe.get(0).getTime();
                        from = from - (from % 60000);
                        found = true;
                        log("[" + inst + "] Найдена история M1 начиная с: " + sdf.format(new Date(from)));
                        break;
                    }
                    from += step;
                }
                if (!found) {
                    log("[" + inst + "] Нет данных M1 за этот период.");
                    return;
                }
            }

            for (long cStart = from; cStart < to; cStart += step) {
                long cEnd = Math.min(cStart + step, to);
                cEnd = cEnd - (cEnd % 60000);
                
                log("[" + inst + "] M1: загрузка " + sdf.format(new Date(cStart)) + " ...");
                
                boolean success = false;
                int retries = 3;
                while (!success && retries > 0) {
                    try {
                        List<IBar> bars = hist.getBars(inst, Period.ONE_MIN, OfferSide.BID, cStart, cEnd);
                        if (bars != null) {
                            for (IBar b : bars) {
                                // ИСКЛЮЧАЕМ ВЫХОДНЫЕ ДНИ: записываем только бары с реальным объемом > 0
                                if (b.getVolume() > 0 && b.getTime() > sessionStartMs) {
                                    writer.writeBar(b.getTime(), b.getOpen(), b.getHigh(), b.getLow(), b.getClose(), b.getVolume());
                                }
                            }
                        }
                        success = true;
                        Thread.sleep(50); // Пауза для защиты от бана сервера
                    } catch (Exception e) {
                        retries--;
                        log("[" + inst + "] Сбой сети (M1). Осталось попыток: " + retries);
                        Thread.sleep(2000);
                        if (retries == 0) throw e;
                    }
                }
            }
        }

        private void loadTicks(IHistory hist, Instrument inst, ScidWriter writer, long from, long to) throws Exception {
            // ШАГ УМЕНЬШЕН ДО 4 ЧАСОВ во избежание ошибки сервера на ликвидных парах
            long step = 4 * 3600 * 1000L; 
            long sessionStartMs = writer.getLastTimeMs(); 
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");

            if (sessionStartMs == 0) {
                log("[" + inst + "] Поиск фактического начала тикового архива...");
                boolean found = false;
                long probeStep = 30L * 86400000L; 
                long probeFrom = from - (from % 60000);
                while (probeFrom < to) {
                    long pEnd = Math.min(probeFrom + probeStep, to);
                    List<IBar> probe = hist.getBars(inst, Period.ONE_MIN, OfferSide.BID, probeFrom, pEnd);
                    if (probe != null && !probe.isEmpty()) {
                        from = probe.get(0).getTime();
                        found = true;
                        log("[" + inst + "] Найдена история ТИКОВ начиная с: " + sdf.format(new Date(from)));
                        break;
                    }
                    probeFrom += probeStep;
                }
                if (!found) {
                    log("[" + inst + "] Нет тиков за этот период.");
                    return;
                }
            }

            for (long cStart = from; cStart < to; cStart += step) {
                long cEnd = Math.min(cStart + step, to);
                
                log("[" + inst + "] ТИКИ: загрузка " + sdf.format(new Date(cStart)) + " ...");
                
                boolean success = false;
                int retries = 3;
                while (!success && retries > 0) {
                    try {
                        List<ITick> ticks = hist.getTicks(inst, cStart, cEnd);
                        if (ticks != null) {
                            for (ITick t : ticks) {
                                if (t.getTime() > sessionStartMs) {
                                    writer.writeTick(t.getTime(), t.getBid(), t.getAsk(), t.getBidVolume(), t.getAskVolume());
                                }
                            }
                        }
                        success = true;
                        Thread.sleep(50); // Пауза для защиты от бана сервера
                    } catch (Exception e) {
                        retries--;
                        log("[" + inst + "] Сбой сети (Тики). Осталось попыток: " + retries);
                        Thread.sleep(2000);
                        if (retries == 0) throw e;
                    }
                }
            }
        }

        @Override public void onStop() throws JFException { writers.values().forEach(ScidWriter::close); }
        @Override public void onBar(Instrument i, Period p, IBar a, IBar b) {}
        @Override public void onMessage(IMessage message) {}
        @Override public void onAccount(IAccount account) {}
    }

    // =========================================================================================
    // БИНАРНАЯ ЗАПИСЬ .SCID 
    // =========================================================================================
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
