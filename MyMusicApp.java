import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * MY MUSIC  -  Java Swing music app  (ADMIN / ARTIST / USER)
 *
 * Compile : javac MyMusicApp.java
 * Run     : java MyMusicApp
 *
 * Demo logins (pehli baar run pe auto-create):
 *     ADMIN  -> admin  / admin123
 *     ARTIST -> artist / artist123
 *     USER   -> user   / user123
 *
 * Sidebar: Home, Library, Create, Chat, Premium, Profile (+ My Songs / Songs / Users role ke hisaab se)
 * Data "data" folder me save hota hai (users, songs, playlists, chat ...).
 *
 * ONLINE MUSIC STREAMING (Jamendo API):
 *   1. devportal.jamendo.com pe free app banao -> client_id (API key) milegi.
 *   2. ADMIN login -> Profile -> "Streaming API key" -> paste -> Save key.
 *      (key data/config.properties me save hoti hai; ya env var JAMENDO_CLIENT_ID)
 *   MP3 play karne ke liye classpath me ye jar chahiye (Maven Central, group com.googlecode.soundlibs):
 *   mp3spi, jlayer, tritonus-share.   Run: java -cp ".;*" MyMusicApp
 */
public class MyMusicApp {

    // ---------- Theme ----------
    static final Color BG    = Color.BLACK;
    static final Color CARD  = new Color(30, 26, 46);
    static final Color PILL  = new Color(38, 34, 52);
    static final Color LINE  = new Color(45, 45, 50);
    static final Color CYAN  = new Color(0, 229, 255);
    static final Color GREEN = new Color(30, 215, 96);
    static final Color GOLD  = new Color(255, 196, 60);
    static final Color TRACK = new Color(75, 75, 80);
    static final String FONT = "Segoe UI";

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) { }
            Color sel = new Color(80, 66, 140);
            UIManager.put("PopupMenu.background", CARD);
            UIManager.put("PopupMenu.border", new LineBorder(new Color(80, 72, 115)));
            UIManager.put("MenuItem.background", CARD);
            UIManager.put("MenuItem.foreground", Color.WHITE);
            UIManager.put("MenuItem.selectionBackground", sel);
            UIManager.put("MenuItem.selectionForeground", Color.WHITE);
            UIManager.put("Menu.background", CARD);
            UIManager.put("Menu.foreground", Color.WHITE);
            UIManager.put("Menu.selectionBackground", sel);
            UIManager.put("Menu.selectionForeground", Color.WHITE);
            UIManager.put("MenuItem.font", new Font(FONT, Font.PLAIN, 15));
            UIManager.put("Menu.font", new Font(FONT, Font.PLAIN, 15));
            Config.load();
            Store.init();
            new LoginFrame().setVisible(true);
        });
    }

    static Graphics2D aa(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    static String fmt(int s) { return String.format("%d:%02d", s / 60, s % 60); }

    static <T extends JComponent> T left(T c) { c.setAlignmentX(Component.LEFT_ALIGNMENT); return c; }

    static void styleField(JTextField f) {
        f.setBackground(CARD);
        f.setForeground(Color.WHITE);
        f.setCaretColor(Color.WHITE);
        f.setFont(new Font(FONT, Font.PLAIN, 16));
        f.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(80, 72, 115), 1, true), new EmptyBorder(8, 12, 8, 12)));
    }

    static String ext(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i).toLowerCase();
    }

    static String dateStr(long ts) { return new SimpleDateFormat("dd MMM yyyy").format(new Date(ts)); }

    // =====================================================================
    //  MODELS
    // =====================================================================
    static class User {
        String name, hash, role, display;
        boolean blocked;
        long premiumUntil;
        boolean isPremium() { return premiumUntil > System.currentTimeMillis(); }
    }

    static class Song {
        int id, plays;
        String title, artist, file, cover = "", status;   // status: PENDING / APPROVED / REJECTED
        boolean online;                                     // Jamendo track (id negative)
        String streamUrl = "", coverUrl = "", artistName = "";
    }

    static class Playlist {
        final String owner;
        String name;
        final List<Integer> ids = new ArrayList<>();
        Playlist(String owner, String name) { this.owner = owner; this.name = name; }
    }

    static class Msg {
        final long ts;
        final String from, to, text;
        Msg(long ts, String from, String to, String text) { this.ts = ts; this.from = from; this.to = to; this.text = text; }
    }

    // =====================================================================
    //  STORE  (file based storage)
    // =====================================================================
    static class Store {
        static final File DIR = new File("data");
        static final File SONG_DIR = new File("data/songs");
        static final File UF = new File(DIR, "users.txt");
        static final File SF = new File(DIR, "songs.txt");
        static final File FF = new File(DIR, "favs.txt");
        static final File OF = new File(DIR, "online.txt");
        static final File PF = new File(DIR, "playlists.txt");
        static final File CF = new File(DIR, "chat.txt");
        static final File CRF = new File(DIR, "chatread.txt");
        static final List<User> users = new ArrayList<>();
        static final List<Song> songs = new ArrayList<>();
        static final Map<String, Set<Integer>> favs = new HashMap<>();
        static final Map<Integer, Song> online = new LinkedHashMap<>();
        static final List<Playlist> playlists = new ArrayList<>();
        static final List<Msg> msgs = new ArrayList<>();
        static final Map<String, Long> lastRead = new HashMap<>();
        static long chatMod = -1;

        static void init() {
            DIR.mkdirs();
            SONG_DIR.mkdirs();
            load();
            if (users.isEmpty()) {
                addUser("admin", "admin123", "ADMIN", "Administrator");
                addUser("artist", "artist123", "ARTIST", "Hansraj Raghuwanshi");
                addUser("user", "user123", "USER", "Demo User");
                seedSongs();
                save();
            }
            reloadChat(true);
        }

        static String clean(String s) {
            return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        }

        static String esc(String s) {
            return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "");
        }

        static String unesc(String s) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(++i);
                    b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
                } else b.append(c);
            }
            return b.toString();
        }

        static String hash(String s) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) { return s; }
        }

        static User addUser(String name, String pw, String role, String display) {
            User u = new User();
            u.name = name; u.hash = hash(pw); u.role = role; u.display = display; u.blocked = false;
            users.add(u);
            return u;
        }

        static User findUser(String name) {
            if (name == null) return null;
            for (User u : users) if (u.name.equalsIgnoreCase(name)) return u;
            return null;
        }

        static int nextSongId() {
            int m = 0;
            for (Song s : songs) m = Math.max(m, s.id);
            return m + 1;
        }

        static Song songById(int id) {
            if (id < 0) return online.get(id);
            for (Song s : songs) if (s.id == id) return s;
            return null;
        }

        static String artistName(Song s) {
            if (s.online) return s.artistName;
            User u = findUser(s.artist);
            return u == null ? "Unknown" : u.display;
        }

        static List<Song> approved() {
            List<Song> l = new ArrayList<>();
            for (Song s : songs) if ("APPROVED".equals(s.status)) l.add(s);
            return l;
        }

        static List<Song> byArtist(String name) {
            List<Song> l = new ArrayList<>();
            for (Song s : songs) if (s.artist.equalsIgnoreCase(name)) l.add(s);
            return l;
        }

        // ---- likes ----
        static boolean isFav(String user, int id) {
            Set<Integer> f = favs.get(user);
            return f != null && f.contains(id);
        }

        static void toggleFav(String user, Song s) {
            if (s.online) online.put(s.id, s);
            Set<Integer> f = favs.computeIfAbsent(user, k -> new LinkedHashSet<>());
            if (!f.remove(s.id)) f.add(s.id);
            save();
        }

        static List<Song> favSongs(String user) {
            List<Song> l = new ArrayList<>();
            Set<Integer> f = favs.get(user);
            if (f == null) return l;
            for (int id : f) {
                Song s = songById(id);
                if (s != null && (s.online || "APPROVED".equals(s.status))) l.add(s);
            }
            return l;
        }

        // ---- playlists ----
        static List<Playlist> playlistsOf(String owner) {
            List<Playlist> l = new ArrayList<>();
            for (Playlist p : playlists) if (p.owner.equalsIgnoreCase(owner)) l.add(p);
            return l;
        }

        static Playlist findPlaylist(String owner, String name) {
            for (Playlist p : playlists)
                if (p.owner.equalsIgnoreCase(owner) && p.name.equalsIgnoreCase(name)) return p;
            return null;
        }

        static Playlist createPlaylist(String owner, String name) {
            name = clean(name == null ? "" : name).trim();
            if (name.isEmpty() || findPlaylist(owner, name) != null) return null;
            Playlist p = new Playlist(owner, name);
            playlists.add(p);
            save();
            return p;
        }

        static void deletePlaylist(Playlist p) { playlists.remove(p); save(); }

        static void addToPlaylist(Playlist p, Song s) {
            if (s.online) online.put(s.id, s);
            if (!p.ids.contains(s.id)) p.ids.add(s.id);
            save();
        }

        static void removeFromPlaylist(Playlist p, Song s) { p.ids.remove(Integer.valueOf(s.id)); save(); }

        static List<Song> songsOf(Playlist p) {
            List<Song> l = new ArrayList<>();
            for (int id : p.ids) {
                Song s = songById(id);
                if (s != null && (s.online || "APPROVED".equals(s.status))) l.add(s);
            }
            return l;
        }

        // ---- delete ----
        static boolean inDataDir(String path) {
            return path != null && path.replace('\\', '/').startsWith("data/songs/");
        }

        static void deleteSong(Song s) {
            songs.remove(s);
            for (Set<Integer> f : favs.values()) f.remove(s.id);
            for (Playlist p : playlists) p.ids.remove(Integer.valueOf(s.id));
            if (inDataDir(s.file)) new File(s.file).delete();
            if (inDataDir(s.cover)) new File(s.cover).delete();
            save();
        }

        static void deleteUser(User u) {
            for (Song s : byArtist(u.name)) deleteSong(s);
            users.remove(u);
            favs.remove(u.name);
            playlists.removeIf(p -> p.owner.equalsIgnoreCase(u.name));
            reloadChat(true);
            msgs.removeIf(m -> m.from.equalsIgnoreCase(u.name) || m.to.equalsIgnoreCase(u.name));
            saveChat();
            save();
        }

        // ---- chat ----
        static void reloadChat(boolean force) {
            long m = CF.exists() ? CF.lastModified() : 0;
            if (!force && m == chatMod) return;
            chatMod = m;
            msgs.clear();
            for (String l : read(CF)) {
                String[] p = l.split("\t", -1);
                if (p.length < 4) continue;
                try { msgs.add(new Msg(Long.parseLong(p[0]), p[1], p[2], unesc(p[3]))); } catch (NumberFormatException ignored) { }
            }
            lastRead.clear();
            for (String l : read(CRF)) {
                String[] p = l.split("\t", -1);
                if (p.length < 3) continue;
                try { lastRead.put(p[0] + "|" + p[1], Long.parseLong(p[2])); } catch (NumberFormatException ignored) { }
            }
        }

        static void saveChat() {
            List<String> l = new ArrayList<>();
            for (Msg m : msgs) l.add(m.ts + "\t" + clean(m.from) + "\t" + clean(m.to) + "\t" + esc(m.text));
            write(CF, l);
            chatMod = CF.exists() ? CF.lastModified() : 0;
            List<String> r = new ArrayList<>();
            for (Map.Entry<String, Long> e : lastRead.entrySet()) {
                String[] k = e.getKey().split("\\|", 2);
                if (k.length == 2) r.add(k[0] + "\t" + k[1] + "\t" + e.getValue());
            }
            write(CRF, r);
        }

        static void sendMsg(String from, String to, String text) {
            reloadChat(true);
            long ts = System.currentTimeMillis();
            if (!msgs.isEmpty()) {
                long last = 0;
                for (Msg m : msgs) last = Math.max(last, m.ts);
                if (ts <= last) ts = last + 1;
            }
            msgs.add(new Msg(ts, from, to, text));
            saveChat();
        }

        static List<Msg> conversation(String me, String partner) {
            List<Msg> l = new ArrayList<>();
            for (Msg m : msgs) {
                if ((m.from.equalsIgnoreCase(me) && m.to.equalsIgnoreCase(partner))
                        || (m.from.equalsIgnoreCase(partner) && m.to.equalsIgnoreCase(me))) l.add(m);
            }
            l.sort((a, b) -> Long.compare(a.ts, b.ts));
            return l;
        }

        static int unread(String me, String partner) {
            long lr = lastRead.getOrDefault(me + "|" + partner, 0L);
            int n = 0;
            for (Msg m : msgs) if (m.from.equalsIgnoreCase(partner) && m.to.equalsIgnoreCase(me) && m.ts > lr) n++;
            return n;
        }

        static int totalUnread(String me) {
            int n = 0;
            for (User u : users) if (!u.name.equalsIgnoreCase(me)) n += unread(me, u.name);
            return n;
        }

        static void markRead(String me, String partner) {
            long mx = 0;
            for (Msg m : msgs) if (m.from.equalsIgnoreCase(partner) && m.to.equalsIgnoreCase(me)) mx = Math.max(mx, m.ts);
            String key = me + "|" + partner;
            if (mx > lastRead.getOrDefault(key, 0L)) { lastRead.put(key, mx); saveChat(); }
        }

        // ---- file io ----
        static List<String> read(File f) {
            try {
                return f.exists() ? Files.readAllLines(f.toPath(), StandardCharsets.UTF_8) : new ArrayList<>();
            } catch (IOException e) { return new ArrayList<>(); }
        }

        static void write(File f, List<String> lines) {
            try { Files.write(f.toPath(), lines, StandardCharsets.UTF_8); } catch (IOException ignored) { }
        }

        static void load() {
            users.clear(); songs.clear(); favs.clear(); online.clear(); playlists.clear();
            for (String l : read(UF)) {
                String[] p = l.split("\t", -1);
                if (p.length < 5) continue;
                User u = new User();
                u.name = p[0]; u.hash = p[1]; u.role = p[2]; u.blocked = p[3].equals("1"); u.display = p[4];
                if (p.length > 5) { try { u.premiumUntil = Long.parseLong(p[5]); } catch (NumberFormatException ignored) { } }
                users.add(u);
            }
            for (String l : read(SF)) {
                String[] p = l.split("\t", -1);
                if (p.length < 7) continue;
                try {
                    Song s = new Song();
                    s.id = Integer.parseInt(p[0]); s.title = p[1]; s.artist = p[2]; s.file = p[3];
                    s.cover = p[4]; s.status = p[5]; s.plays = Integer.parseInt(p[6]);
                    songs.add(s);
                } catch (NumberFormatException ignored) { }
            }
            for (String l : read(OF)) {
                String[] p = l.split("\t", -1);
                if (p.length < 5) continue;
                try {
                    Song s = new Song();
                    s.online = true; s.id = Integer.parseInt(p[0]); s.title = p[1]; s.artistName = p[2];
                    s.streamUrl = p[3]; s.coverUrl = p[4]; s.artist = ""; s.file = ""; s.status = "APPROVED";
                    online.put(s.id, s);
                } catch (NumberFormatException ignored) { }
            }
            for (String l : read(FF)) {
                String[] p = l.split("\t", -1);
                if (p.length < 2) continue;
                Set<Integer> set = new LinkedHashSet<>();
                for (String x : p[1].split(",")) {
                    try { if (!x.isEmpty()) set.add(Integer.parseInt(x.trim())); } catch (NumberFormatException ignored) { }
                }
                favs.put(p[0], set);
            }
            for (String l : read(PF)) {
                String[] p = l.split("\t", -1);
                if (p.length < 3) continue;
                Playlist pl = new Playlist(p[0], p[1]);
                for (String x : p[2].split(",")) {
                    try { if (!x.isEmpty()) pl.ids.add(Integer.parseInt(x.trim())); } catch (NumberFormatException ignored) { }
                }
                playlists.add(pl);
            }
        }

        static String join(Collection<Integer> ids) {
            StringBuilder sb = new StringBuilder();
            for (int id : ids) { if (sb.length() > 0) sb.append(','); sb.append(id); }
            return sb.toString();
        }

        static void save() {
            List<String> ul = new ArrayList<>();
            for (User u : users)
                ul.add(clean(u.name) + "\t" + u.hash + "\t" + u.role + "\t" + (u.blocked ? "1" : "0") + "\t"
                        + clean(u.display) + "\t" + u.premiumUntil);
            write(UF, ul);
            List<String> sl = new ArrayList<>();
            for (Song s : songs)
                sl.add(s.id + "\t" + clean(s.title) + "\t" + clean(s.artist) + "\t" + clean(s.file) + "\t"
                        + clean(s.cover) + "\t" + s.status + "\t" + s.plays);
            write(SF, sl);
            List<String> ol = new ArrayList<>();
            for (Song s : online.values())
                ol.add(s.id + "\t" + clean(s.title) + "\t" + clean(s.artistName) + "\t" + clean(s.streamUrl) + "\t" + clean(s.coverUrl));
            write(OF, ol);
            List<String> fl = new ArrayList<>();
            for (Map.Entry<String, Set<Integer>> e : favs.entrySet()) fl.add(clean(e.getKey()) + "\t" + join(e.getValue()));
            write(FF, fl);
            List<String> pl = new ArrayList<>();
            for (Playlist p : playlists) pl.add(clean(p.owner) + "\t" + clean(p.name) + "\t" + join(p.ids));
            write(PF, pl);
        }

        // ---- demo songs (generated WAV tones so app works without any audio file) ----
        static void seedSongs() {
            String[] names = {"Har har Mahadev", "Shiv Tandav", "Sunrise Melody", "Ocean Breeze", "Mountain Echo", "Golden Field"};
            double[] scale = {261.63, 293.66, 329.63, 392.00, 440.00, 523.25, 587.33};
            for (int i = 0; i < names.length; i++) {
                File f = new File(SONG_DIR, "demo" + (i + 1) + ".wav");
                try { makeTone(f, new Random(i + 7), scale, i % 2 == 0 ? 0.45 : 0.6, 36); } catch (Exception ignored) { }
                Song s = new Song();
                s.id = nextSongId(); s.title = names[i]; s.artist = "artist";
                s.file = f.getPath().replace('\\', '/'); s.status = "APPROVED"; s.plays = (names.length - i) * 3;
                songs.add(s);
            }
        }

        static void makeTone(File f, Random rnd, double[] scale, double noteSec, int count) throws Exception {
            float sr = 22050f;
            int per = (int) (sr * noteSec);
            byte[] buf = new byte[count * per * 2];
            int idx = rnd.nextInt(scale.length);
            for (int n = 0; n < count; n++) {
                idx = Math.max(0, Math.min(scale.length - 1, idx + rnd.nextInt(5) - 2));
                double fr = scale[idx];
                for (int i = 0; i < per; i++) {
                    double t = i / (double) sr;
                    double env = Math.min(1, i / 200.0) * Math.min(1, (per - i) / 2000.0) * Math.exp(-t * 1.5);
                    double v = 0.6 * Math.sin(2 * Math.PI * fr * t) + 0.25 * Math.sin(4 * Math.PI * fr * t);
                    short sv = (short) (v * env * 14000);
                    int p = (n * per + i) * 2;
                    buf[p] = (byte) (sv & 0xff);
                    buf[p + 1] = (byte) ((sv >> 8) & 0xff);
                }
            }
            AudioFormat af = new AudioFormat(sr, 16, 1, true, false);
            AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(buf), af, buf.length / 2);
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, f);
        }
    }

    // =====================================================================
    //  CONFIG (API key: data/config.properties)
    // =====================================================================
    static class Config {
        static final File F = new File("data/config.properties");
        static final Properties P = new Properties();

        static void load() {
            try (InputStream in = new FileInputStream(F)) { P.load(in); } catch (IOException ignored) { }
        }

        static String apiKey() {
            String k = P.getProperty("jamendo.client_id", "").trim();
            if (k.isEmpty()) {
                String e = System.getenv("JAMENDO_CLIENT_ID");
                if (e != null) k = e.trim();
            }
            return k;
        }

        static void setApiKey(String k) {
            P.setProperty("jamendo.client_id", k.trim());
            F.getParentFile().mkdirs();
            try (OutputStream o = new FileOutputStream(F)) { P.store(o, "My Music config"); } catch (IOException ignored) { }
        }
    }

    // =====================================================================
    //  tiny JSON parser
    // =====================================================================
    static class Json {
        final String s;
        int i = 0;

        Json(String s) { this.s = s; }

        static Object parse(String s) { return new Json(s).val(); }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object val() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    i++; // ':'
                    m.put(k, val());
                    ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(val());
                    ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.valueOf(s.substring(st, i));
        }

        String str() {
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
            return b.toString();
        }
    }

    // =====================================================================
    //  API (Jamendo: https://api.jamendo.com/v3.0/tracks)
    // =====================================================================
    static class Api {
        static String enc(String s) {
            try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
        }

        static String str(Object o) { return o == null ? "" : String.valueOf(o); }

        static List<Song> tracks(String query) throws Exception { return tracks(query, Config.apiKey(), 20); }

        static List<Song> tracks(String query, String key, int limit) throws Exception {
            if (key == null || key.isEmpty()) throw new IOException("API key set nahi hai.");
            String url = "https://api.jamendo.com/v3.0/tracks/?client_id=" + enc(key)
                    + "&format=json&limit=" + limit + "&audioformat=mp31&imagesize=300";
            url += (query == null || query.isEmpty()) ? "&order=popularity_week" : "&search=" + enc(query);
            Object root = Json.parse(http(url));
            if (!(root instanceof Map)) throw new IOException("Unexpected response");
            Map<?, ?> m = (Map<?, ?>) root;
            Object hd = m.get("headers");
            if (hd instanceof Map) {
                Map<?, ?> h = (Map<?, ?>) hd;
                if (h.get("status") != null && !"success".equals(str(h.get("status"))))
                    throw new IOException(str(h.get("error_message")).isEmpty() ? "API error" : str(h.get("error_message")));
            }
            List<Song> out = new ArrayList<>();
            Object res = m.get("results");
            if (!(res instanceof List)) return out;
            for (Object o : (List<?>) res) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> t = (Map<?, ?>) o;
                String audio = str(t.get("audio"));
                if (audio.isEmpty()) continue;
                try {
                    Song s = new Song();
                    s.online = true;
                    s.id = -Integer.parseInt(str(t.get("id")));
                    s.title = str(t.get("name"));
                    s.artist = "";
                    s.artistName = str(t.get("artist_name"));
                    s.streamUrl = audio;
                    s.coverUrl = str(t.get("image"));
                    s.file = ""; s.status = "APPROVED";
                    out.add(s);
                } catch (NumberFormatException ignored) { }
            }
            return out;
        }

        static String http(String u) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "MyMusicSwing/1.0");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            try (ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                String body = bo.toString("UTF-8");
                if (code >= 400) throw new IOException("HTTP " + code + " " + body);
                return body;
            } finally { in.close(); }
        }

        static File download(String u, File dest) throws IOException {
            dest.getParentFile().mkdirs();
            File tmp = new File(dest.getPath() + ".part");
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "MyMusicSwing/1.0");
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            if (!tmp.renameTo(dest)) {
                Files.copy(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
            }
            return dest;
        }

        static BufferedImage image(String u) {
            try { return ImageIO.read(new URL(u)); } catch (Exception e) { return null; }
        }
    }

    // =====================================================================
    //  SHARED WIDGETS
    // =====================================================================
    static class RoundButton extends JButton {
        Color bg, fg;
        boolean hover;

        RoundButton(String text, Color bg, Color fg, int w, int h) {
            super(text);
            this.bg = bg; this.fg = fg;
            setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(new Font(FONT, Font.PLAIN, 16));
            setPreferredSize(new Dimension(w, h));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            Color c = bg;
            if (hover) c = new Color(Math.min(255, c.getRed() + 28), Math.min(255, c.getGreen() + 28), Math.min(255, c.getBlue() + 28));
            g2.setColor(c);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.setColor(fg);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    static class IconButton extends JComponent {
        private String type;
        private Color fg;
        private final Color circle;
        private boolean hover;
        private final List<ActionListener> listeners = new ArrayList<>();

        IconButton(String type, int size, Color circle, Color fg) {
            this.type = type; this.circle = circle; this.fg = fg;
            setPreferredSize(new Dimension(size, size));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) {
                    for (ActionListener l : listeners)
                        l.actionPerformed(new ActionEvent(IconButton.this, ActionEvent.ACTION_PERFORMED, IconButton.this.type));
                }
            });
        }

        void addActionListener(ActionListener l) { listeners.add(l); }
        void setType(String t) { type = t; repaint(); }
        void setFg(Color c) { fg = c; repaint(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            int w = getWidth(), h = getHeight();
            Color ic = fg, cut = BG;
            if (circle != null) {
                Color c = circle;
                if (hover) c = circle.equals(Color.WHITE) ? new Color(225, 225, 225) : new Color(60, 56, 78);
                g2.setColor(c);
                g2.fillOval(0, 0, w - 1, h - 1);
                cut = c;
            } else if (hover) {
                if (fg.equals(Color.WHITE)) ic = CYAN;
                else if (fg.equals(Color.BLACK)) ic = new Color(90, 90, 90);
            }
            int is = (int) (Math.min(w, h) * (circle != null ? 0.48 : 0.72));
            Icons.paint(g2, type, (w - is) / 2, (h - is) / 2, is, ic, cut);
            g2.dispose();
        }
    }

    static class ProgressBar extends JComponent {
        double value = 0;
        Color fill = GREEN;
        Consumer<Double> onSeek;

        ProgressBar() {
            setPreferredSize(new Dimension(500, 20));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { seek(e); }
                @Override public void mouseDragged(MouseEvent e) { seek(e); }
                void seek(MouseEvent e) {
                    value = Math.max(0, Math.min(1, e.getX() / (double) Math.max(1, getWidth())));
                    repaint();
                    if (onSeek != null) onSeek.accept(value);
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        void setValue(double v) { value = v; repaint(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            int y = getHeight() / 2 - 3;
            g2.setColor(TRACK);
            g2.fillRoundRect(0, y, getWidth(), 6, 6, 6);
            g2.setColor(fill);
            g2.fillRoundRect(0, y, (int) (getWidth() * value), 6, 6, 6);
            g2.dispose();
        }
    }

    static class DarkScrollBarUI extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() { thumbColor = new Color(120, 120, 135); trackColor = new Color(14, 14, 16); }
        @Override protected JButton createDecreaseButton(int o) { return zero(); }
        @Override protected JButton createIncreaseButton(int o) { return zero(); }
        private JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            Graphics2D g2 = aa(g);
            g2.setColor(isThumbRollover() ? new Color(160, 160, 175) : thumbColor);
            g2.fillRoundRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2, 8, 8);
            g2.dispose();
        }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(trackColor); g.fillRect(r.x, r.y, r.width, r.height);
        }
    }

    /** Panel jo viewport ki width follow karta hai (wrap / responsive pages ke liye) */
    static class ScrollPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 30; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return o == SwingConstants.VERTICAL ? Math.max(60, r.height - 40) : Math.max(60, r.width - 40);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Responsive card grid - window ki width ke hisaab se columns badalte hain */
    static class CardGrid extends JPanel {
        static final int CW = 240, CH = 280;

        CardGrid() {
            setLayout(null);
            setOpaque(false);
            addComponentListener(new ComponentAdapter() {
                int lastW = -1;
                @Override public void componentResized(ComponentEvent e) {
                    if (getWidth() != lastW) { lastW = getWidth(); revalidate(); }
                }
            });
        }

        int cols() {
            int w = getWidth() > 0 ? getWidth() : (getParent() != null && getParent().getWidth() > 0 ? getParent().getWidth() : 900);
            return Math.max(1, (w - 8) / CW);
        }

        @Override public Dimension getPreferredSize() {
            int n = getComponentCount(), c = cols();
            int rows = Math.max(1, (n + c - 1) / c);
            return new Dimension(getWidth() > 0 ? getWidth() : 900, rows * CH + 10);
        }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        @Override public void doLayout() {
            int c = cols();
            for (int i = 0; i < getComponentCount(); i++)
                getComponent(i).setBounds(4 + (i % c) * CW, (i / c) * CH + 2, 220, 258);
        }
    }

    /** Chat bubble */
    static class Bubble extends JComponent {
        static final Font TF = new Font(FONT, Font.PLAIN, 15), SF = new Font(FONT, Font.PLAIN, 11);
        final String time;
        final boolean mine;
        final List<String> lines = new ArrayList<>();
        final int w, h, lh;

        Bubble(String text, String time, boolean mine) {
            this.time = time; this.mine = mine;
            FontMetrics fm = getFontMetrics(TF);
            wrap(text, fm, 420);
            int tw = 0;
            for (String l : lines) tw = Math.max(tw, fm.stringWidth(l));
            lh = fm.getHeight();
            w = Math.max(tw, getFontMetrics(SF).stringWidth(time)) + 28;
            h = 8 + lines.size() * lh + 14 + 6;
            setPreferredSize(new Dimension(w, h));
            setOpaque(false);
        }

        void wrap(String text, FontMetrics fm, int max) {
            for (String para : text.split("\n", -1)) {
                if (para.isEmpty()) { lines.add(""); continue; }
                StringBuilder cur = new StringBuilder();
                for (String word : para.split(" ")) {
                    while (fm.stringWidth(word) > max) {
                        int k = 1;
                        while (k < word.length() && fm.stringWidth(word.substring(0, k + 1)) <= max) k++;
                        if (cur.length() > 0) { lines.add(cur.toString()); cur.setLength(0); }
                        lines.add(word.substring(0, k));
                        word = word.substring(k);
                    }
                    String test = cur.length() == 0 ? word : cur + " " + word;
                    if (fm.stringWidth(test) <= max) { cur.setLength(0); cur.append(test); }
                    else { lines.add(cur.toString()); cur.setLength(0); cur.append(word); }
                }
                lines.add(cur.toString());
            }
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            g2.setColor(mine ? new Color(96, 76, 214) : new Color(40, 36, 60));
            g2.fillRoundRect(0, 0, w, h, 18, 18);
            g2.setFont(TF);
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            int y = 8 + fm.getAscent();
            for (String l : lines) { g2.drawString(l, 14, y); y += lh; }
            g2.setFont(SF);
            g2.setColor(new Color(255, 255, 255, 140));
            g2.drawString(time, w - 14 - g2.getFontMetrics().stringWidth(time), h - 8);
            g2.dispose();
        }
    }

    // =====================================================================
    //  ICONS (Java2D)
    // =====================================================================
    static class Icons {
        static void paint(Graphics2D g0, String type, int x, int y, int s, Color c, Color cut) {
            Graphics2D g = (Graphics2D) g0.create();
            g.translate(x, y);
            g.setColor(c);
            g.setStroke(new BasicStroke(Math.max(2f, s / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double S = s;
            switch (type) {
                case "home": {
                    Path2D p = new Path2D.Double();
                    p.moveTo(S * .5, S * .06); p.lineTo(S * .98, S * .52); p.lineTo(S * .84, S * .52);
                    p.lineTo(S * .84, S * .92); p.lineTo(S * .6, S * .92); p.lineTo(S * .6, S * .64);
                    p.lineTo(S * .4, S * .64); p.lineTo(S * .4, S * .92); p.lineTo(S * .16, S * .92);
                    p.lineTo(S * .16, S * .52); p.lineTo(S * .02, S * .52); p.closePath();
                    g.fill(p); break;
                }
                case "plusCircle": {
                    g.fillOval(0, 0, s, s);
                    g.setColor(cut);
                    g.drawLine((int) (S * .5), (int) (S * .27), (int) (S * .5), (int) (S * .73));
                    g.drawLine((int) (S * .27), (int) (S * .5), (int) (S * .73), (int) (S * .5));
                    break;
                }
                case "book": {
                    g.fillRoundRect((int) (S * .15), (int) (S * .02), (int) (S * .7), s - 2, 8, 8);
                    g.setColor(cut);
                    g.drawLine((int) (S * .3), (int) (S * .32), (int) (S * .7), (int) (S * .32));
                    g.drawLine((int) (S * .3), (int) (S * .55), (int) (S * .55), (int) (S * .55));
                    break;
                }
                case "person": {
                    g.fillOval((int) (S * .27), (int) (S * .02), (int) (S * .46), (int) (S * .46));
                    g.fillRoundRect((int) (S * .08), (int) (S * .55), (int) (S * .84), (int) (S * .42), 22, 22);
                    break;
                }
                case "user": {
                    g.fillOval(0, 0, s, s);
                    g.setColor(cut);
                    g.fillOval((int) (S * .34), (int) (S * .2), (int) (S * .32), (int) (S * .32));
                    g.fill(new Arc2D.Double(S * .2, S * .56, S * .6, S * .6, 0, 180, Arc2D.PIE));
                    break;
                }
                case "chat": {
                    g.fill(new RoundRectangle2D.Double(S * .04, S * .08, S * .92, S * .66, S * .3, S * .3));
                    g.fill(new Polygon(new int[]{(int) (S * .22), (int) (S * .16), (int) (S * .5)},
                            new int[]{(int) (S * .66), (int) (S * .96), (int) (S * .7)}, 3));
                    g.setColor(cut);
                    for (int i = 0; i < 3; i++) g.fillOval((int) (S * (.24 + i * .22)), (int) (S * .34), (int) (S * .12), (int) (S * .12));
                    break;
                }
                case "dollar": {
                    g.setFont(new Font(FONT, Font.BOLD, (int) (S * 1.15)));
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString("$", (int) ((S - fm.stringWidth("$")) / 2), (int) (S * .92));
                    break;
                }
                case "note": {
                    g.fillOval((int) (S * .08), (int) (S * .6), (int) (S * .4), (int) (S * .3));
                    g.fill(new Rectangle2D.Double(S * .4, S * .1, S * .1, S * .68));
                    Path2D p = new Path2D.Double();
                    p.moveTo(S * .5, S * .1); p.curveTo(S * .72, S * .15, S * .88, S * .3, S * .86, S * .52);
                    p.lineTo(S * .78, S * .52); p.curveTo(S * .75, S * .38, S * .65, S * .3, S * .5, S * .28); p.closePath();
                    g.fill(p); break;
                }
                case "menu": {
                    float th = (float) (S * .12);
                    for (int i = 0; i < 3; i++)
                        g.fill(new RoundRectangle2D.Double(S * .08, S * (.2 + i * .28), S * .84, th, th, th));
                    break;
                }
                case "search": {
                    g.draw(new Ellipse2D.Double(S * .1, S * .08, S * .55, S * .55));
                    g.drawLine((int) (S * .58), (int) (S * .58), (int) (S * .9), (int) (S * .9));
                    break;
                }
                case "bell": {
                    Path2D p = new Path2D.Double();
                    p.moveTo(S * .15, S * .78); p.curveTo(S * .3, S * .65, S * .25, S * .5, S * .27, S * .38);
                    p.curveTo(S * .3, S * .15, S * .7, S * .15, S * .73, S * .38);
                    p.curveTo(S * .75, S * .5, S * .7, S * .65, S * .85, S * .78); p.closePath();
                    g.fill(p);
                    g.fillOval((int) (S * .4), (int) (S * .8), (int) (S * .2), (int) (S * .16));
                    g.fillOval((int) (S * .44), (int) (S * .04), (int) (S * .12), (int) (S * .12));
                    break;
                }
                case "play": {
                    Path2D p = new Path2D.Double();
                    p.moveTo(S * .2, S * .05); p.lineTo(S * .95, S * .5); p.lineTo(S * .2, S * .95); p.closePath();
                    g.fill(p); break;
                }
                case "pause": {
                    g.fill(new RoundRectangle2D.Double(S * .15, S * .08, S * .24, S * .84, 4, 4));
                    g.fill(new RoundRectangle2D.Double(S * .61, S * .08, S * .24, S * .84, 4, 4));
                    break;
                }
                case "prev": {
                    g.fill(new Rectangle2D.Double(S * .1, S * .12, S * .14, S * .76));
                    Path2D p = new Path2D.Double();
                    p.moveTo(S * .92, S * .1); p.lineTo(S * .3, S * .5); p.lineTo(S * .92, S * .9); p.closePath();
                    g.fill(p); break;
                }
                case "next": {
                    g.fill(new Rectangle2D.Double(S * .76, S * .12, S * .14, S * .76));
                    Path2D p = new Path2D.Double();
                    p.moveTo(S * .08, S * .1); p.lineTo(S * .7, S * .5); p.lineTo(S * .08, S * .9); p.closePath();
                    g.fill(p); break;
                }
                case "rewind": {
                    for (int i = 0; i < 2; i++) {
                        double o = i * S * .45;
                        Path2D p = new Path2D.Double();
                        p.moveTo(S * .5 + o, S * .12); p.lineTo(S * .02 + o, S * .5); p.lineTo(S * .5 + o, S * .88); p.closePath();
                        g.fill(p);
                    }
                    break;
                }
                case "arrowL": {
                    Path2D p = new Path2D.Double();
                    p.moveTo(S * .8, S * .05); p.lineTo(S * .1, S * .5); p.lineTo(S * .8, S * .95); p.closePath();
                    g.fill(p); break;
                }
                case "arrowR": {
                    Path2D p = new Path2D.Double();
                    p.moveTo(S * .2, S * .05); p.lineTo(S * .9, S * .5); p.lineTo(S * .2, S * .95); p.closePath();
                    g.fill(p); break;
                }
                case "shuffle": {
                    g.draw(new Line2D.Double(S * .05, S * .25, S * .35, S * .25));
                    g.draw(new CubicCurve2D.Double(S * .35, S * .25, S * .55, S * .25, S * .5, S * .75, S * .72, S * .75));
                    g.draw(new Line2D.Double(S * .05, S * .75, S * .35, S * .75));
                    g.draw(new CubicCurve2D.Double(S * .35, S * .75, S * .55, S * .75, S * .5, S * .25, S * .72, S * .25));
                    arrowHead(g, S * .72, S * .25, S); arrowHead(g, S * .72, S * .75, S);
                    break;
                }
                case "repeat": {
                    g.draw(new Line2D.Double(S * .22, S * .3, S * .78, S * .3));
                    g.draw(new Line2D.Double(S * .78, S * .3, S * .88, S * .4));
                    g.draw(new Line2D.Double(S * .88, S * .4, S * .88, S * .5));
                    g.draw(new Line2D.Double(S * .78, S * .7, S * .22, S * .7));
                    g.draw(new Line2D.Double(S * .22, S * .7, S * .12, S * .6));
                    g.draw(new Line2D.Double(S * .12, S * .6, S * .12, S * .5));
                    arrowHead(g, S * .78, S * .3, S);
                    g.fill(new Polygon(new int[]{(int) (S * .22), (int) (S * .22), (int) (S * .02)},
                            new int[]{(int) (S * .55), (int) (S * .85), (int) (S * .7)}, 3));
                    break;
                }
                case "volume": case "mute": {
                    Path2D p = new Path2D.Double();
                    p.moveTo(S * .05, S * .38); p.lineTo(S * .25, S * .38); p.lineTo(S * .5, S * .12);
                    p.lineTo(S * .5, S * .88); p.lineTo(S * .25, S * .62); p.lineTo(S * .05, S * .62); p.closePath();
                    g.fill(p);
                    if (type.equals("volume")) {
                        g.draw(new Arc2D.Double(S * .38, S * .3, S * .4, S * .4, -50, 100, Arc2D.OPEN));
                        g.draw(new Arc2D.Double(S * .3, S * .15, S * .65, S * .7, -55, 110, Arc2D.OPEN));
                    } else {
                        g.draw(new Line2D.Double(S * .62, S * .35, S * .92, S * .65));
                        g.draw(new Line2D.Double(S * .92, S * .35, S * .62, S * .65));
                    }
                    break;
                }
                case "fullscreen": {
                    double a = S * .06, b = S * .94, l = S * .3;
                    g.draw(new Line2D.Double(a, a, a + l, a)); g.draw(new Line2D.Double(a, a, a, a + l));
                    g.draw(new Line2D.Double(b, a, b - l, a)); g.draw(new Line2D.Double(b, a, b, a + l));
                    g.draw(new Line2D.Double(a, b, a + l, b)); g.draw(new Line2D.Double(a, b, a, b - l));
                    g.draw(new Line2D.Double(b, b, b - l, b)); g.draw(new Line2D.Double(b, b, b, b - l));
                    break;
                }
                case "headphones": {
                    g.draw(new Arc2D.Double(S * .12, S * .1, S * .76, S * .8, 0, 180, Arc2D.OPEN));
                    g.fill(new RoundRectangle2D.Double(S * .06, S * .5, S * .22, S * .38, 8, 8));
                    g.fill(new RoundRectangle2D.Double(S * .72, S * .5, S * .22, S * .38, 8, 8));
                    break;
                }
                default: break;
            }
            g.dispose();
        }

        private static void arrowHead(Graphics2D g, double x, double y, double S) {
            g.fill(new Polygon(new int[]{(int) x, (int) x, (int) (x + S * .2)},
                    new int[]{(int) (y - S * .13), (int) (y + S * .13), (int) y}, 3));
        }
    }

    // =====================================================================
    //  COVER ART (cached + pre-rendered => smooth scrolling)
    // =====================================================================
    static class Art {
        static final Map<Integer, BufferedImage> cache = new HashMap<>();
        static final Map<String, BufferedImage> coverCache = new HashMap<>();
        static final Map<String, BufferedImage> cardCache = new HashMap<>();
        static final Set<String> loading = new HashSet<>();

        static BufferedImage get(int k) { return cache.computeIfAbsent(k, Art::make); }

        static BufferedImage localCover(String path) {
            BufferedImage c = coverCache.get(path);
            if (c == null) {
                try { c = ImageIO.read(new File(path)); } catch (Exception ignored) { }
                if (c != null) coverCache.put(path, c);
            }
            return c;
        }

        /** raw image (original size) */
        static BufferedImage forSong(Song s) { return forSong(s, null); }

        static BufferedImage forSong(Song s, Runnable done) {
            if (s.online) {
                BufferedImage c = coverCache.get(s.coverUrl);
                if (c != null) return c;
                triggerLoad(s, done);
                return get(Math.abs(s.id) % 8);
            }
            if (s.cover != null && !s.cover.isEmpty()) {
                BufferedImage c = localCover(s.cover);
                if (c != null) return c;
            }
            return get(Math.abs(s.id) % 8);
        }

        static void triggerLoad(Song s, Runnable done) {
            if (s.coverUrl.isEmpty() || !loading.add(s.coverUrl)) return;
            final String url = s.coverUrl;
            new Thread(() -> {
                BufferedImage im = Api.image(url);
                if (im != null) SwingUtilities.invokeLater(() -> {
                    coverCache.put(url, im);
                    if (done != null) done.run();
                });
            }).start();
        }

        /** 192x192 rounded card image, cached */
        static BufferedImage card(Song s, Runnable done) {
            String key;
            BufferedImage raw = null;
            if (s.online) {
                raw = coverCache.get(s.coverUrl);
                if (raw == null) triggerLoad(s, done);
                key = raw != null ? "u:" + s.coverUrl : "ph:" + (Math.abs(s.id) % 8);
            } else if (s.cover != null && !s.cover.isEmpty()) {
                raw = localCover(s.cover);
                key = raw != null ? "f:" + s.cover : "ph:" + (Math.abs(s.id) % 8);
            } else key = "ph:" + (Math.abs(s.id) % 8);
            BufferedImage c = cardCache.get(key);
            if (c == null) {
                if (raw == null) raw = get(Math.abs(s.id) % 8);
                c = rounded(raw, 192, 14);
                cardCache.put(key, c);
            }
            return c;
        }

        static BufferedImage rounded(BufferedImage src, int size, int arc) {
            BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = aa(out.getGraphics());
            g.setClip(new RoundRectangle2D.Float(0, 0, size, size, arc, arc));
            int w = src.getWidth(), h = src.getHeight(), side = Math.min(w, h);
            g.drawImage(src, 0, 0, size, size, (w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2, null);
            g.dispose();
            return out;
        }

        static BufferedImage make(int k) {
            int n = 384;
            BufferedImage im = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = aa(im.getGraphics());
            switch (k) {
                case 0:
                    g.setColor(new Color(196, 52, 38)); g.fillRect(0, 0, n, n);
                    g.setColor(new Color(150, 35, 28));
                    for (int i = 0; i < n; i += 22) g.drawLine(0, i, n, i);
                    g.setColor(new Color(225, 190, 130)); g.fillRoundRect(110, 140, 100, 190, 8, 8);
                    g.setColor(new Color(190, 215, 235)); g.fillRect(130, 170, 60, 70);
                    g.setColor(new Color(190, 190, 195)); g.fillRect(0, 330, n, 54);
                    g.setColor(Color.DARK_GRAY); g.setStroke(new BasicStroke(5));
                    g.drawOval(190, 290, 60, 60); g.drawOval(280, 290, 60, 60);
                    g.drawLine(220, 320, 250, 280); g.drawLine(250, 280, 310, 320);
                    break;
                case 1:
                    g.setPaint(new GradientPaint(0, 0, new Color(70, 80, 85), 0, 200, new Color(150, 160, 165)));
                    g.fillRect(0, 0, n, 200);
                    g.setColor(new Color(90, 110, 120)); g.fillRect(0, 200, n, 80);
                    g.setColor(new Color(190, 170, 130)); g.fillPolygon(new int[]{0, n, n, 0}, new int[]{270, 300, n, n}, 4);
                    g.setColor(new Color(40, 55, 45)); g.fillPolygon(new int[]{0, 120, 60, 0}, new int[]{230, 330, n, n}, 4);
                    break;
                case 2:
                    g.setPaint(new GradientPaint(0, 0, new Color(130, 195, 235), 0, 150, new Color(220, 235, 240)));
                    g.fillRect(0, 0, n, 150);
                    g.setColor(new Color(20, 55, 40)); g.fillPolygon(new int[]{140, n, n, 140}, new int[]{50, 20, 190, 190}, 4);
                    g.setPaint(new GradientPaint(0, 190, new Color(20, 160, 185), 0, n, new Color(10, 90, 120)));
                    g.fillRect(0, 190, n, n);
                    g.setColor(new Color(20, 30, 35)); g.fillArc(30, 300, 340, 160, 0, 180);
                    g.setColor(new Color(205, 55, 45)); g.fillOval(130, 200, 90, 130);
                    g.setColor(new Color(150, 90, 50)); g.fillOval(140, 140, 70, 80);
                    break;
                case 3:
                    g.setPaint(new GradientPaint(0, 0, new Color(25, 110, 190), 0, n, new Color(15, 175, 215)));
                    g.fillRect(0, 0, n, n);
                    g.setColor(new Color(255, 255, 255, 220));
                    g.fillOval(50, 40, 140, 60); g.fillOval(150, 70, 120, 50); g.fillOval(280, 30, 100, 50);
                    g.setColor(Color.WHITE); g.fillRect(230, 270, 40, 12);
                    break;
                case 4:
                    g.setPaint(new GradientPaint(0, 0, new Color(70, 150, 225), 0, 230, new Color(190, 225, 245)));
                    g.fillRect(0, 0, n, 230);
                    g.setPaint(new GradientPaint(0, 230, new Color(10, 90, 180), 0, n, new Color(5, 45, 130)));
                    g.fillRect(0, 230, n, n);
                    g.setColor(new Color(50, 70, 140)); g.fillPolygon(new int[]{220, 260, 330, 360}, new int[]{232, 190, 205, 232}, 4);
                    break;
                case 5:
                    g.setPaint(new GradientPaint(0, 0, new Color(150, 175, 195), 0, 200, new Color(215, 225, 230)));
                    g.fillRect(0, 0, n, n);
                    g.setColor(new Color(110, 130, 145)); g.fillPolygon(new int[]{0, 120, 200, 300, n, n, 0}, new int[]{200, 90, 150, 60, 130, n, n}, 7);
                    g.setColor(new Color(70, 85, 70)); g.fillPolygon(new int[]{0, 160, 250, n, n, 0}, new int[]{300, 220, 250, 190, n, n}, 6);
                    break;
                case 6:
                    g.setPaint(new GradientPaint(0, 0, new Color(40, 55, 40), n, 0, new Color(255, 235, 200)));
                    g.fillRect(0, 0, n, n);
                    g.setColor(new Color(20, 18, 16)); g.fillOval(130, 100, 90, 100); g.fillRoundRect(60, 180, 240, 220, 100, 100);
                    break;
                default:
                    g.setPaint(new GradientPaint(0, 0, new Color(235, 215, 150), 0, 200, new Color(245, 235, 200)));
                    g.fillRect(0, 0, n, 200);
                    g.setPaint(new GradientPaint(0, 200, new Color(190, 150, 70), 0, n, new Color(120, 90, 30)));
                    g.fillRect(0, 200, n, n);
                    break;
            }
            g.dispose();
            return im;
        }
    }

    // =====================================================================
    //  USER DIALOG (register + admin "add user")
    // =====================================================================
    static boolean userDialog(Component parent, boolean adminMode) {
        JTextField name = new JTextField(), user = new JTextField();
        JPasswordField p1 = new JPasswordField(), p2 = new JPasswordField();
        JComboBox<String> role = new JComboBox<>(adminMode
                ? new String[]{"USER", "ARTIST", "ADMIN"} : new String[]{"USER", "ARTIST"});
        JPanel pnl = new JPanel(new GridLayout(0, 2, 8, 8));
        pnl.add(new JLabel("Full name")); pnl.add(name);
        pnl.add(new JLabel("Username")); pnl.add(user);
        pnl.add(new JLabel("Password")); pnl.add(p1);
        pnl.add(new JLabel("Confirm password")); pnl.add(p2);
        pnl.add(new JLabel("Account type")); pnl.add(role);
        while (true) {
            int r = JOptionPane.showConfirmDialog(parent, pnl, adminMode ? "Add User" : "Register",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return false;
            String un = user.getText().trim();
            String pw = new String(p1.getPassword());
            String err = null;
            if (!un.matches("[A-Za-z0-9_]{3,20}")) err = "Username 3-20 characters ka ho (letters, numbers, _).";
            else if (Store.findUser(un) != null) err = "Ye username pehle se maujood hai.";
            else if (pw.length() < 4) err = "Password kam se kam 4 characters ka ho.";
            else if (!pw.equals(new String(p2.getPassword()))) err = "Dono password match nahi karte.";
            if (err != null) { JOptionPane.showMessageDialog(parent, err); continue; }
            String disp = name.getText().trim().isEmpty() ? un : name.getText().trim();
            Store.addUser(un, pw, (String) role.getSelectedItem(), disp);
            Store.save();
            JOptionPane.showMessageDialog(parent, "Account ban gaya: " + un + " (" + role.getSelectedItem() + ")");
            return true;
        }
    }

    // =====================================================================
    //  LOGIN FRAME
    // =====================================================================
    static class LoginFrame extends JFrame {
        String role = "USER";
        final RoundButton[] roleBtns = new RoundButton[3];
        final String[] roles = {"USER", "ARTIST", "ADMIN"};
        final JTextField userField = new JTextField();
        final JPasswordField passField = new JPasswordField();
        final JLabel err = new JLabel(" ");

        LoginFrame() {
            super("My Music - Login");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(760, 700);
            setLocationRelativeTo(null);
            JPanel root = new JPanel(new GridBagLayout());
            root.setBackground(BG);
            setContentPane(root);

            JPanel card = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = aa(g);
                    g2.setColor(CARD);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 28, 28);
                    g2.dispose();
                }
            };
            card.setOpaque(false);
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBorder(new EmptyBorder(30, 50, 30, 50));
            card.setPreferredSize(new Dimension(430, 600));

            JLabel title = new JLabel("My Music");
            title.setFont(new Font(FONT, Font.BOLD, 36));
            title.setForeground(CYAN);
            JLabel sub = new JLabel("Sign in to continue");
            sub.setFont(new Font(FONT, Font.PLAIN, 16));
            sub.setForeground(Color.LIGHT_GRAY);

            JPanel roleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            roleRow.setOpaque(false);
            roleRow.setMaximumSize(new Dimension(330, 42));
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                roleBtns[i] = new RoundButton(roles[i], PILL, Color.WHITE, 100, 38);
                roleBtns[i].setFont(new Font(FONT, Font.BOLD, 14));
                roleBtns[i].addActionListener(e -> selectRole(roles[idx]));
                roleRow.add(roleBtns[i]);
            }
            selectRole("USER");

            styleField(userField);
            styleField(passField);
            userField.setMaximumSize(new Dimension(330, 44));
            passField.setMaximumSize(new Dimension(330, 44));
            ActionListener doLogin = e -> login();
            userField.addActionListener(doLogin);
            passField.addActionListener(doLogin);

            err.setForeground(new Color(255, 100, 100));
            err.setFont(new Font(FONT, Font.PLAIN, 14));

            RoundButton loginBtn = new RoundButton("Login", CYAN, Color.BLACK, 330, 46);
            loginBtn.setFont(new Font(FONT, Font.BOLD, 18));
            loginBtn.addActionListener(doLogin);
            loginBtn.setMaximumSize(new Dimension(330, 46));

            RoundButton regBtn = new RoundButton("Create new account", PILL, Color.WHITE, 330, 42);
            regBtn.setMaximumSize(new Dimension(330, 42));
            regBtn.addActionListener(e -> userDialog(this, false));

            JLabel hint = new JLabel("<html><div style='width:320px;color:#9a9a9a'>Demo: admin / admin123 &nbsp;|&nbsp; "
                    + "artist / artist123 &nbsp;|&nbsp; user / user123</div></html>");
            hint.setFont(new Font(FONT, Font.PLAIN, 13));

            card.add(left(title));
            card.add(left(sub));
            card.add(Box.createVerticalStrut(22));
            card.add(left(label("Login as")));
            card.add(Box.createVerticalStrut(6));
            card.add(left(roleRow));
            card.add(Box.createVerticalStrut(16));
            card.add(left(label("Username")));
            card.add(Box.createVerticalStrut(6));
            card.add(left(userField));
            card.add(Box.createVerticalStrut(14));
            card.add(left(label("Password")));
            card.add(Box.createVerticalStrut(6));
            card.add(left(passField));
            card.add(Box.createVerticalStrut(10));
            card.add(left(err));
            card.add(Box.createVerticalStrut(10));
            card.add(left(loginBtn));
            card.add(Box.createVerticalStrut(12));
            card.add(left(regBtn));
            card.add(Box.createVerticalStrut(18));
            card.add(left(hint));
            root.add(card);
        }

        JLabel label(String t) {
            JLabel l = new JLabel(t);
            l.setForeground(Color.WHITE);
            l.setFont(new Font(FONT, Font.PLAIN, 15));
            return l;
        }

        void selectRole(String r) {
            role = r;
            for (int i = 0; i < 3; i++) {
                boolean on = roles[i].equals(r);
                roleBtns[i].bg = on ? CYAN : PILL;
                roleBtns[i].fg = on ? Color.BLACK : Color.WHITE;
                roleBtns[i].repaint();
            }
        }

        void login() {
            String u = userField.getText().trim();
            String p = new String(passField.getPassword());
            Store.load();
            User usr = Store.findUser(u);
            if (usr == null || !usr.hash.equals(Store.hash(p))) { err.setText("Galat username ya password."); return; }
            if (!usr.role.equals(role)) { err.setText("Ye account " + usr.role + " ka hai - upar sahi role chuno."); return; }
            if (usr.blocked) { err.setText("Aapka account block hai. Admin se contact karo."); return; }
            dispose();
            new MainFrame(usr).setVisible(true);
        }
    }

    // =====================================================================
    //  MAIN FRAME
    // =====================================================================
    static class Nav {
        final String key;
        final Supplier<JComponent> sup;
        Nav(String key, Supplier<JComponent> sup) { this.key = key; this.sup = sup; }
    }

    static class MainFrame extends JFrame {
        final User me;
        final JPanel holder = new JPanel(new BorderLayout());
        JScrollPane sidebarScroll;
        JPanel sidebar;
        JTextField searchField;
        JLabel premiumBadge;
        Nav current;
        String activeKey = "";
        final Deque<Nav> history = new ArrayDeque<>();
        boolean onLibrary = false;
        static final Map<String, List<Song>> onlineCache = new HashMap<>();

        // chat
        User chatPartner;
        JPanel chatMsgs;
        JScrollPane chatScroll;
        JList<User> chatList;
        JLabel chatHeader;
        JTextField chatInput;
        int chatShown = -1, chatBadge = -1, pollCount = 0;

        // player
        final JLabel songTitle = new JLabel("Koi gaana select nahi");
        final JLabel songArtist = new JLabel("-");
        final JLabel timeNow = new JLabel("0:00");
        final JLabel timeEnd = new JLabel("0:00");
        final JLabel art = new JLabel("Album Art", SwingConstants.CENTER);
        final ProgressBar progress = new ProgressBar();
        final ProgressBar volBar = new ProgressBar();
        IconButton playBtn, shuffleBtn, repeatBtn, muteBtn;
        List<Song> queue = new ArrayList<>();
        int qIndex = -1, playToken;
        Song nowPlaying;
        Clip clip;
        boolean playing, shuffle, repeat, muted, fullscreen;
        double volume = 1.0;
        long startedAt;
        final Random rnd = new Random();
        final List<Song> recent = new ArrayList<>();
        final Timer ticker;

        MainFrame(User me) {
            super("My Music - " + me.display + " (" + me.role + ")");
            this.me = me;
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { stopClip(); System.exit(0); }
            });
            setSize(1280, 740);
            setMinimumSize(new Dimension(940, 580));
            setLocationRelativeTo(null);
            getContentPane().setBackground(BG);
            setLayout(new BorderLayout());
            holder.setBackground(BG);

            add(buildTopBar(), BorderLayout.NORTH);
            buildSidebar();
            add(sidebarScroll, BorderLayout.WEST);
            add(holder, BorderLayout.CENTER);
            add(buildPlayer(), BorderLayout.SOUTH);

            go("home", this::pageHome);
            ticker = new Timer(250, e -> tick());
            ticker.start();
        }

        // ---------------- navigation ----------------
        void go(String key, Supplier<JComponent> s) {
            if (current != null) {
                history.push(current);
                if (history.size() > 40) history.removeLast();
            }
            showPage(new Nav(key, s));
        }

        void showPage(Nav n) {
            current = n;
            activeKey = n.key;
            onLibrary = "library".equals(n.key);
            holder.removeAll();
            holder.add(n.sup.get(), BorderLayout.CENTER);
            holder.revalidate();
            holder.repaint();
            if (sidebar != null) sidebar.repaint();
        }

        void refresh() { showPage(current); }

        void back() { if (!history.isEmpty()) showPage(history.pop()); }

        void msg(String m) { JOptionPane.showMessageDialog(this, m); }

        // ---------------- top bar ----------------
        JComponent buildTopBar() {
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(BG);
            bar.setPreferredSize(new Dimension(0, 72));
            bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE));

            JPanel lft = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            lft.setOpaque(false);
            lft.setBorder(new EmptyBorder(16, 28, 0, 0));
            lft.setPreferredSize(new Dimension(320, 72));
            IconButton menu = new IconButton("menu", 40, null, Color.WHITE);
            menu.addActionListener(e -> { sidebarScroll.setVisible(!sidebarScroll.isVisible()); revalidate(); repaint(); });
            JLabel title = new JLabel("My Music");
            title.setFont(new Font(FONT, Font.BOLD, 26));
            title.setForeground(CYAN);
            title.setBorder(new EmptyBorder(0, 40, 0, 0));
            lft.add(menu);
            lft.add(title);

            JPanel center = new JPanel(new FlowLayout(FlowLayout.CENTER, 22, 17));
            center.setOpaque(false);
            IconButton rewind = new IconButton("rewind", 38, Color.WHITE, Color.BLACK);
            rewind.addActionListener(e -> back());
            IconButton home = new IconButton("home", 38, Color.WHITE, Color.BLACK);
            home.addActionListener(e -> go("home", this::pageHome));
            center.add(rewind);
            center.add(buildSearch());
            center.add(home);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 21));
            right.setOpaque(false);
            right.setBorder(new EmptyBorder(0, 0, 0, 20));
            right.setPreferredSize(new Dimension(330, 72));
            premiumBadge = new JLabel("PREMIUM");
            premiumBadge.setFont(new Font(FONT, Font.BOLD, 12));
            premiumBadge.setForeground(Color.BLACK);
            premiumBadge.setOpaque(true);
            premiumBadge.setBackground(GOLD);
            premiumBadge.setBorder(new EmptyBorder(3, 8, 3, 8));
            updateBadge();
            JLabel roleLbl = new JLabel(me.role);
            roleLbl.setFont(new Font(FONT, Font.BOLD, 13));
            roleLbl.setForeground(me.role.equals("ADMIN") ? new Color(255, 110, 110)
                    : me.role.equals("ARTIST") ? new Color(255, 180, 70) : CYAN);
            IconButton bell = new IconButton("bell", 30, null, Color.WHITE);
            bell.addActionListener(e -> bell());
            IconButton usr = new IconButton("user", 30, null, Color.WHITE);
            usr.addActionListener(e -> go("profile", this::pageProfile));
            right.add(premiumBadge);
            right.add(roleLbl);
            right.add(bell);
            right.add(usr);

            bar.add(lft, BorderLayout.WEST);
            bar.add(center, BorderLayout.CENTER);
            bar.add(right, BorderLayout.EAST);
            return bar;
        }

        void updateBadge() { if (premiumBadge != null) premiumBadge.setVisible(me.isPremium()); }

        JComponent buildSearch() {
            JPanel box = new JPanel(new BorderLayout()) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = aa(g);
                    g2.setColor(Color.WHITE);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    g2.dispose();
                }
            };
            box.setOpaque(false);
            box.setPreferredSize(new Dimension(400, 38));
            searchField = new JTextField("Search...");
            searchField.setBorder(new EmptyBorder(0, 14, 0, 0));
            searchField.setOpaque(false);
            searchField.setForeground(Color.GRAY);
            searchField.setFont(new Font(FONT, Font.PLAIN, 15));
            searchField.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) {
                    if (searchField.getText().equals("Search...")) { searchField.setText(""); searchField.setForeground(Color.BLACK); }
                }
                @Override public void focusLost(FocusEvent e) {
                    if (searchField.getText().isEmpty()) { searchField.setText("Search..."); searchField.setForeground(Color.GRAY); }
                }
            });
            searchField.addActionListener(e -> doSearch());
            IconButton s = new IconButton("search", 34, null, Color.BLACK);
            s.addActionListener(e -> doSearch());
            box.add(searchField, BorderLayout.CENTER);
            box.add(s, BorderLayout.EAST);
            return box;
        }

        void doSearch() {
            String q = searchField.getText().trim();
            if (q.isEmpty() || q.equals("Search...")) return;
            go("search", () -> pageSearch(q));
        }

        void bell() {
            StringBuilder m = new StringBuilder();
            boolean toSongs = false;
            if (me.role.equals("ADMIN")) {
                long n = Store.songs.stream().filter(s -> "PENDING".equals(s.status)).count();
                m.append(n).append(" song(s) approval ka wait kar rahe hain.\n");
                toSongs = n > 0;
            } else if (me.role.equals("ARTIST")) {
                List<Song> mine = Store.byArtist(me.name);
                long p = mine.stream().filter(s -> "PENDING".equals(s.status)).count();
                long r = mine.stream().filter(s -> "REJECTED".equals(s.status)).count();
                m.append(p).append(" song pending approval, ").append(r).append(" rejected.\n");
            }
            Store.reloadChat(false);
            int un = Store.totalUnread(me.name);
            m.append(un).append(" unread chat message(s).");
            JOptionPane.showMessageDialog(this, m.toString(), "Notifications", JOptionPane.INFORMATION_MESSAGE);
            if (toSongs) go("songs", this::pageAdminSongs);
        }

        // ---------------- sidebar ----------------
        void buildSidebar() {
            sidebar = new JPanel();
            sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
            sidebar.setBackground(BG);
            sidebar.setBorder(new EmptyBorder(20, 0, 10, 0));

            addSide("home", "home", "Home", () -> go("home", this::pageHome), null);
            addSide("library", "book", "Library", () -> go("library", this::pageLibrary), null);
            addSide("create", "plusCircle", "Create", () -> go("create", this::pageCreate), null);
            if (me.role.equals("ARTIST"))
                addSide("mysongs", "note", "My Songs", () -> go("mysongs", this::pageMySongs), null);
            if (me.role.equals("ADMIN")) {
                addSide("songs", "note", "Songs", () -> go("songs", this::pageAdminSongs), null);
                addSide("users", "person", "Users", () -> go("users", this::pageAdminUsers), null);
            }
            addSide("chat", "chat", "Chat", () -> go("chat", this::pageChat), () -> Math.max(0, chatBadge));
            addSide("premium", "dollar", "Premium", () -> go("premium", this::pagePremium), null);
            addSide("profile", "user", "Profile", () -> go("profile", this::pageProfile), null);

            sidebarScroll = new JScrollPane(sidebar, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            sidebarScroll.setBorder(null);
            sidebarScroll.getViewport().setBackground(BG);
            sidebarScroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
            sidebarScroll.getVerticalScrollBar().setPreferredSize(new Dimension(5, 0));
            sidebarScroll.getVerticalScrollBar().setUI(new DarkScrollBarUI());
            sidebarScroll.getVerticalScrollBar().setUnitIncrement(20);
            sidebarScroll.setPreferredSize(new Dimension(104, 0));
        }

        void addSide(String key, String icon, String text, Runnable action, IntSupplier badge) {
            sidebar.add(new SideItem(key, icon, text, action, badge));
            sidebar.add(Box.createVerticalStrut(6));
        }

        class SideItem extends JPanel {
            final String key, icon, text;
            final IntSupplier badge;
            boolean hover;

            SideItem(String key, String icon, String text, Runnable action, IntSupplier badge) {
                this.key = key; this.icon = icon; this.text = text; this.badge = badge;
                setOpaque(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                Dimension d = new Dimension(100, 60);
                setPreferredSize(d); setMaximumSize(d); setMinimumSize(d);
                setAlignmentX(Component.CENTER_ALIGNMENT);
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                    @Override public void mouseClicked(MouseEvent e) { action.run(); }
                });
            }

            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = aa(g);
                int w = getWidth(), h = getHeight();
                boolean act = key.equals(activeKey);
                if (act) {
                    g2.setColor(new Color(0, 229, 255, 28));
                    g2.fillRoundRect(8, 0, w - 16, h, 14, 14);
                    g2.setColor(CYAN);
                    g2.fillRoundRect(0, 14, 4, h - 28, 4, 4);
                } else if (hover) {
                    g2.setColor(new Color(255, 255, 255, 24));
                    g2.fillRoundRect(8, 0, w - 16, h, 14, 14);
                }
                Color c = act ? CYAN : Color.WHITE;
                Icons.paint(g2, icon, w / 2 - 13, 6, 26, c, BG);
                g2.setFont(new Font(FONT, Font.PLAIN, 13));
                g2.setColor(c);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(text, (w - fm.stringWidth(text)) / 2, 50);
                int n = badge == null ? 0 : badge.getAsInt();
                if (n > 0) {
                    g2.setColor(new Color(235, 60, 80));
                    g2.fillOval(w / 2 + 6, 2, 20, 20);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font(FONT, Font.BOLD, 11));
                    String t = n > 9 ? "9+" : String.valueOf(n);
                    g2.drawString(t, w / 2 + 16 - g2.getFontMetrics().stringWidth(t) / 2, 16);
                }
                g2.dispose();
            }
        }

        // =================================================================
        //  PAGE HELPERS
        // =================================================================
        ScrollPanel newCol() {
            ScrollPanel col = new ScrollPanel();
            col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
            col.setBackground(BG);
            col.setBorder(new EmptyBorder(14, 12, 24, 12));
            return col;
        }

        ScrollPanel form() {
            ScrollPanel p = newCol();
            p.setBorder(new EmptyBorder(24, 30, 30, 30));
            return p;
        }

        JScrollPane scroll(JComponent col) {
            JScrollPane sp = new JScrollPane(col, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            sp.setBorder(null);
            sp.getViewport().setBackground(BG);
            sp.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
            sp.getVerticalScrollBar().setUnitIncrement(30);
            sp.getVerticalScrollBar().setPreferredSize(new Dimension(12, 0));
            sp.getVerticalScrollBar().setUI(new DarkScrollBarUI());
            return sp;
        }

        Component gap() { return Box.createVerticalStrut(26); }

        JLabel bigTitle(String t) {
            JLabel l = new JLabel(t);
            l.setFont(new Font(FONT, Font.BOLD, 28));
            l.setForeground(Color.WHITE);
            return l;
        }

        JLabel text(String t, int size, Color c) {
            JLabel l = new JLabel(t);
            l.setFont(new Font(FONT, Font.PLAIN, size));
            l.setForeground(c);
            return l;
        }

        JMenuItem mi(String t, Runnable r) {
            JMenuItem it = new JMenuItem(t);
            it.setBackground(CARD);
            it.setForeground(Color.WHITE);
            it.setOpaque(true);
            it.setFont(new Font(FONT, Font.PLAIN, 15));
            it.addActionListener(e -> r.run());
            return it;
        }

        // =================================================================
        //  PAGES: home / library / search / view all
        // =================================================================
        JComponent pageHome() {
            ScrollPanel col = newCol();
            List<Song> ap = Store.approved();
            List<Song> trending = new ArrayList<>(ap);
            trending.sort((a, b) -> b.plays - a.plays);
            List<Song> latest = new ArrayList<>(ap);
            latest.sort((a, b) -> b.id - a.id);
            col.add(left(section("Trending", trending)));
            col.add(gap());
            col.add(left(section("New Releases", latest)));
            col.add(gap());
            col.add(left(onlineSection("Online - Trending (Jamendo)", "")));
            List<Song> fav = Store.favSongs(me.name);
            if (!fav.isEmpty()) {
                col.add(gap());
                col.add(left(section("Your Liked Songs", fav)));
            }
            if (!recent.isEmpty()) {
                col.add(gap());
                col.add(left(section("Recently Played", recent)));
            }
            return scroll(col);
        }

        JComponent pageLibrary() {
            ScrollPanel col = newCol();
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.setBorder(new EmptyBorder(0, 4, 18, 4));
            top.add(bigTitle("Your Library"), BorderLayout.WEST);
            RoundButton np = new RoundButton("+ New playlist", CYAN, Color.BLACK, 150, 40);
            np.addActionListener(e -> go("create", this::pageCreate));
            JPanel npw = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            npw.setOpaque(false);
            npw.add(np);
            top.add(npw, BorderLayout.EAST);
            col.add(left(top));
            col.add(left(section("Liked Songs", Store.favSongs(me.name))));
            for (Playlist pl : Store.playlistsOf(me.name)) {
                col.add(gap());
                col.add(left(section(pl.name, Store.songsOf(pl), null, pl.name)));
            }
            col.add(gap());
            col.add(left(section("Recently Played", recent)));
            return scroll(col);
        }

        JComponent pageSearch(String q) {
            ScrollPanel col = newCol();
            String ql = q.toLowerCase();
            List<Song> res = new ArrayList<>();
            for (Song s : Store.approved())
                if (s.title.toLowerCase().contains(ql) || Store.artistName(s).toLowerCase().contains(ql)) res.add(s);
            col.add(left(section("Results for \"" + q + "\" (" + res.size() + ")", res)));
            col.add(gap());
            col.add(left(onlineSection("Online results", q)));
            return scroll(col);
        }

        /** View all: saare gaane grid me, vertical scroll ke saath */
        JComponent pageViewAll(String title, List<Song> list, String onlineQuery, String playlistName) {
            ScrollPanel col = newCol();
            final List<Song> items = new ArrayList<>(list);
            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(false);
            head.setBorder(new EmptyBorder(0, 4, 18, 4));
            JPanel titleBox = new JPanel();
            titleBox.setOpaque(false);
            titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
            JLabel count = text(items.size() + " songs", 15, Color.GRAY);
            titleBox.add(left(bigTitle(title)));
            titleBox.add(left(count));
            head.add(titleBox, BorderLayout.WEST);
            RoundButton playAll = new RoundButton("Play all", GREEN, Color.BLACK, 120, 42);
            playAll.setFont(new Font(FONT, Font.BOLD, 16));
            playAll.addActionListener(e -> { if (!items.isEmpty()) playSong(items.get(0), items); });
            RoundButton shuf = new RoundButton("Shuffle", PILL, Color.WHITE, 120, 42);
            shuf.addActionListener(e -> {
                if (items.isEmpty()) return;
                List<Song> sh = new ArrayList<>(items);
                Collections.shuffle(sh);
                playSong(sh.get(0), sh);
            });
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
            btns.setOpaque(false);
            btns.add(playAll);
            btns.add(shuf);
            head.add(btns, BorderLayout.EAST);
            col.add(left(head));

            CardGrid grid = new CardGrid();
            fillGrid(grid, items, playlistName);
            if (items.isEmpty()) col.add(left(text("Yahan abhi koi gaana nahi hai.", 16, Color.GRAY)));
            col.add(left(grid));

            if (onlineQuery != null && !Config.apiKey().isEmpty()) {
                final String ck = "all:" + onlineQuery;
                List<Song> cached = onlineCache.get(ck);
                if (cached != null) {
                    items.clear(); items.addAll(cached);
                    fillGrid(grid, items, playlistName);
                    count.setText(items.size() + " songs");
                } else {
                    count.setText(items.size() + " songs  (aur load ho rahe hain...)");
                    new SwingWorker<List<Song>, Void>() {
                        @Override protected List<Song> doInBackground() throws Exception { return Api.tracks(onlineQuery, Config.apiKey(), 60); }
                        @Override protected void done() {
                            try {
                                List<Song> more = get();
                                onlineCache.put(ck, more);
                                items.clear(); items.addAll(more);
                                fillGrid(grid, items, playlistName);
                                count.setText(items.size() + " songs");
                            } catch (Exception ex) { count.setText(items.size() + " songs"); }
                        }
                    }.execute();
                }
            }
            return scroll(col);
        }

        void fillGrid(CardGrid grid, List<Song> items, String playlistName) {
            grid.removeAll();
            List<Song> ctx = new ArrayList<>(items);
            for (Song s : ctx) grid.add(new Card(s, ctx, playlistName));
            grid.revalidate();
            grid.repaint();
        }

        JComponent infoBox(String title, String message) {
            JPanel b = new JPanel(new BorderLayout());
            b.setOpaque(false);
            JPanel h = new JPanel(new BorderLayout());
            h.setOpaque(false);
            h.setBorder(new EmptyBorder(0, 4, 12, 4));
            h.add(bigTitle(title), BorderLayout.WEST);
            JLabel l = text(message, 16, Color.GRAY);
            l.setBorder(new EmptyBorder(10, 6, 20, 0));
            b.add(h, BorderLayout.NORTH);
            b.add(l, BorderLayout.CENTER);
            return b;
        }

        /** Jamendo se online tracks (background thread me) */
        JComponent onlineSection(String title, String query) {
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.setOpaque(false);
            if (Config.apiKey().isEmpty()) {
                wrap.add(infoBox(title, "Online streaming ke liye API key set nahi hai."
                        + (me.role.equals("ADMIN") ? " Profile -> Streaming API key me daalo." : " Admin se API key set karne ko bolo.")),
                        BorderLayout.CENTER);
                wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
                return wrap;
            }
            List<Song> cached = onlineCache.get(query);
            if (cached != null) {
                wrap.add(section(title, cached, query, null), BorderLayout.CENTER);
                wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 370));
                return wrap;
            }
            wrap.add(infoBox(title, "Loading..."), BorderLayout.CENTER);
            wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
            new SwingWorker<List<Song>, Void>() {
                @Override protected List<Song> doInBackground() throws Exception { return Api.tracks(query); }
                @Override protected void done() {
                    wrap.removeAll();
                    try {
                        List<Song> l = get();
                        onlineCache.put(query, l);
                        wrap.add(section(title, l, query, null), BorderLayout.CENTER);
                        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 370));
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        wrap.add(infoBox(title, "Load nahi hua: " + c.getMessage()), BorderLayout.CENTER);
                    }
                    wrap.revalidate();
                    wrap.repaint();
                }
            }.execute();
            return wrap;
        }

        // ---------- horizontal row ----------
        JComponent section(String title, List<Song> list) { return section(title, list, null, null); }

        JComponent section(String title, List<Song> list, String onlineQuery, String playlistName) {
            JPanel sec = new JPanel(new BorderLayout());
            sec.setOpaque(false);

            JPanel header = new JPanel(new BorderLayout());
            header.setOpaque(false);
            header.setBorder(new EmptyBorder(0, 4, 12, 4));
            header.add(bigTitle(title), BorderLayout.WEST);
            sec.add(header, BorderLayout.NORTH);

            if (list.isEmpty()) {
                JLabel e = text("Abhi yahan koi gaana nahi hai.", 16, Color.GRAY);
                e.setBorder(new EmptyBorder(10, 6, 20, 0));
                sec.add(e, BorderLayout.CENTER);
                sec.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
                return sec;
            }

            List<Song> ctx = new ArrayList<>(list);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 0));
            row.setOpaque(false);
            for (Song s : ctx) row.add(new Card(s, ctx, playlistName));
            row.setPreferredSize(new Dimension(ctx.size() * 240 + 20, 262));

            JScrollPane rs = new JScrollPane(row, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            rs.setBorder(null);
            rs.setOpaque(false);
            rs.getViewport().setOpaque(false);
            rs.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
            rs.setWheelScrollingEnabled(false);
            rs.getHorizontalScrollBar().setUI(new DarkScrollBarUI());
            rs.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 8));
            rs.getHorizontalScrollBar().setUnitIncrement(30);
            rs.setPreferredSize(new Dimension(100, 282));
            rs.addMouseWheelListener(e -> wheelRow(rs, e));

            IconButton prev = new IconButton("arrowL", 44, PILL, Color.WHITE);
            IconButton next = new IconButton("arrowR", 44, PILL, Color.WHITE);
            prev.addActionListener(e -> scrollRow(rs, -500));
            next.addActionListener(e -> scrollRow(rs, 500));
            RoundButton viewAll = new RoundButton("View all", PILL, Color.WHITE, 96, 40);
            viewAll.addActionListener(e -> go("viewall", () -> pageViewAll(title, ctx, onlineQuery, playlistName)));
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
            controls.setOpaque(false);
            controls.add(prev); controls.add(next); controls.add(viewAll);
            header.add(controls, BorderLayout.EAST);

            sec.add(rs, BorderLayout.CENTER);
            sec.setMaximumSize(new Dimension(Integer.MAX_VALUE, 370));
            return sec;
        }

        /** Shift + wheel => left/right ; normal wheel => page upar/niche */
        void wheelRow(JScrollPane rs, MouseWheelEvent e) {
            if (e.isShiftDown()) {
                JScrollBar hb = rs.getHorizontalScrollBar();
                hb.setValue(hb.getValue() + e.getUnitsToScroll() * 40);
            } else {
                Container p = SwingUtilities.getAncestorOfClass(JScrollPane.class, rs);
                if (p != null) p.dispatchEvent(SwingUtilities.convertMouseEvent(rs, e, p));
            }
        }

        void scrollRow(JScrollPane sp, int delta) {
            JViewport vp = sp.getViewport();
            int max = Math.max(0, vp.getView().getWidth() - vp.getWidth());
            int target = Math.max(0, Math.min(max, vp.getViewPosition().x + delta));
            Timer t = new Timer(10, null);
            t.addActionListener(e -> {
                Point cur = vp.getViewPosition();
                int diff = target - cur.x;
                if (Math.abs(diff) <= 4) { vp.setViewPosition(new Point(target, 0)); t.stop(); }
                else vp.setViewPosition(new Point(cur.x + diff / 4 + (diff > 0 ? 1 : -1), 0));
            });
            t.start();
        }

        // ---------- Card ----------
        class Card extends JPanel {
            final Song song;
            final List<Song> ctx;
            final String playlistName;
            boolean hover;
            final Rectangle heartR = new Rectangle(166, 18, 36, 36);
            final Rectangle plusR = new Rectangle(26, 18, 36, 36);

            Card(Song s, List<Song> ctx, String playlistName) {
                this.song = s; this.ctx = ctx; this.playlistName = playlistName;
                setOpaque(false);
                setPreferredSize(new Dimension(220, 258));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                    @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) menu(e.getX(), e.getY()); }
                    @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) menu(e.getX(), e.getY()); }
                    @Override public void mouseClicked(MouseEvent e) {
                        if (!SwingUtilities.isLeftMouseButton(e)) return;
                        if (heartR.contains(e.getPoint())) {
                            Store.toggleFav(me.name, song);
                            if (onLibrary) refresh(); else repaint();
                        } else if (plusR.contains(e.getPoint())) {
                            menu(plusR.x, plusR.y + plusR.height);
                        } else playSong(song, Card.this.ctx);
                    }
                });
            }

            void menu(int x, int y) { showCardMenu(this, x, y, song, ctx, playlistName); }

            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = aa(g);
                g2.setColor(hover ? new Color(44, 38, 68) : CARD);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                g2.drawImage(Art.card(song, this::repaint), 14, 14, null);

                boolean fav = Store.isFav(me.name, song.id);
                if (hover || fav) {
                    g2.setColor(new Color(0, 0, 0, 140));
                    g2.fillOval(heartR.x, heartR.y, 36, 36);
                    Path2D h = new Path2D.Double();
                    double x = heartR.x + 9, y = heartR.y + 9, s = 18;
                    h.moveTo(x + s / 2, y + s * .95);
                    h.curveTo(x - s * .3, y + s * .45, x + s * .1, y - s * .2, x + s / 2, y + s * .28);
                    h.curveTo(x + s * .9, y - s * .2, x + s * 1.3, y + s * .45, x + s / 2, y + s * .95);
                    h.closePath();
                    if (fav) { g2.setColor(new Color(255, 70, 100)); g2.fill(h); }
                    else { g2.setColor(Color.WHITE); g2.setStroke(new BasicStroke(1.8f)); g2.draw(h); g2.setStroke(new BasicStroke(1f)); }
                }
                if (hover) {
                    g2.setColor(new Color(0, 0, 0, 140));
                    g2.fillOval(plusR.x, plusR.y, 36, 36);
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(plusR.x + 11, plusR.y + 18, plusR.x + 25, plusR.y + 18);
                    g2.drawLine(plusR.x + 18, plusR.y + 11, plusR.x + 18, plusR.y + 25);
                    g2.setStroke(new BasicStroke(1f));
                    // big play button
                    g2.setColor(GREEN);
                    g2.fillOval(158, 158, 42, 42);
                    Icons.paint(g2, "play", 170, 169, 20, Color.BLACK, GREEN);
                }

                boolean isNow = song == nowPlaying;
                g2.setColor(isNow ? GREEN : Color.WHITE);
                g2.setFont(new Font(FONT, Font.PLAIN, 17));
                g2.drawString(clipText(song.title, g2, 190), 14, 232);
                g2.setColor(new Color(170, 170, 180));
                g2.setFont(new Font(FONT, Font.PLAIN, 13));
                g2.drawString(clipText(Store.artistName(song), g2, 190), 14, 250);
                g2.dispose();
            }

            String clipText(String t, Graphics2D g2, int w) {
                FontMetrics fm = g2.getFontMetrics();
                if (fm.stringWidth(t) <= w) return t;
                while (t.length() > 1 && fm.stringWidth(t + "...") > w) t = t.substring(0, t.length() - 1);
                return t + "...";
            }
        }

        void showCardMenu(Component inv, int x, int y, Song s, List<Song> ctx, String playlistName) {
            JPopupMenu m = new JPopupMenu();
            m.add(mi("Play", () -> playSong(s, ctx)));
            m.add(mi(Store.isFav(me.name, s.id) ? "Unlike" : "Like", () -> {
                Store.toggleFav(me.name, s);
                if (onLibrary) refresh(); else holder.repaint();
            }));
            JMenu add = new JMenu("Add to playlist");
            add.setBackground(CARD);
            add.setForeground(Color.WHITE);
            add.setOpaque(true);
            add.setFont(new Font(FONT, Font.PLAIN, 15));
            for (Playlist p : Store.playlistsOf(me.name)) {
                add.add(mi(p.name, () -> {
                    Store.addToPlaylist(p, s);
                    msg("\"" + s.title + "\" ko \"" + p.name + "\" me add kar diya.");
                    if (onLibrary) refresh();
                }));
            }
            add.add(mi("+ New playlist...", () -> {
                String n = JOptionPane.showInputDialog(this, "Playlist ka naam:");
                if (n == null) return;
                Playlist p = Store.createPlaylist(me.name, n);
                if (p == null) { msg("Naam khali hai ya is naam ki playlist pehle se hai."); return; }
                Store.addToPlaylist(p, s);
                msg("Playlist \"" + p.name + "\" ban gayi aur gaana add ho gaya.");
                if (onLibrary) refresh();
            }));
            m.add(add);
            if (playlistName != null) {
                m.add(mi("Remove from \"" + playlistName + "\"", () -> {
                    Playlist p = Store.findPlaylist(me.name, playlistName);
                    if (p != null) { Store.removeFromPlaylist(p, s); refresh(); }
                }));
            }
            m.add(mi(s.online ? "Download (online tracks ke liye nahi)" : "Download (Premium)", () -> download(s)));
            m.show(inv, x, y);
        }

        boolean canDownload() { return me.isPremium() || me.role.equals("ADMIN"); }

        void download(Song s) {
            if (!canDownload()) {
                int r = JOptionPane.showConfirmDialog(this, "Download sirf Premium users ke liye hai.\nPremium page kholein?",
                        "Premium", JOptionPane.YES_NO_OPTION);
                if (r == JOptionPane.YES_OPTION) go("premium", this::pagePremium);
                return;
            }
            if (s.online) { msg("Online tracks sirf stream ho sakte hain, download nahi."); return; }
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File(s.title.replaceAll("[\\\\/:*?\"<>|]", "_") + ext(s.file)));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try {
                Files.copy(new File(s.file).toPath(), fc.getSelectedFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
                msg("Download ho gaya: " + fc.getSelectedFile().getName());
            } catch (IOException ex) { msg("Download fail: " + ex.getMessage()); }
        }

        // =================================================================
        //  PAGE: Create (playlists + upload)
        // =================================================================
        JComponent pageCreate() {
            ScrollPanel p = form();
            p.add(left(bigTitle("Create")));
            p.add(Box.createVerticalStrut(22));
            p.add(left(text("New playlist", 20, Color.WHITE)));
            p.add(Box.createVerticalStrut(10));
            JTextField nm = new JTextField();
            styleField(nm);
            nm.setMaximumSize(new Dimension(360, 44));
            RoundButton mk = new RoundButton("Create playlist", CYAN, Color.BLACK, 180, 42);
            Runnable create = () -> {
                Playlist pl = Store.createPlaylist(me.name, nm.getText());
                if (pl == null) { msg("Naam khali hai ya is naam ki playlist pehle se hai."); return; }
                refresh();
            };
            nm.addActionListener(e -> create.run());
            mk.addActionListener(e -> create.run());
            p.add(left(nm));
            p.add(Box.createVerticalStrut(12));
            p.add(left(mk));

            p.add(Box.createVerticalStrut(34));
            p.add(left(text("Your playlists", 20, Color.WHITE)));
            p.add(Box.createVerticalStrut(10));
            List<Playlist> mine = Store.playlistsOf(me.name);
            if (mine.isEmpty()) p.add(left(text("Abhi koi playlist nahi hai. Upar naam likh ke banao, phir kisi card ke + button se gaane add karo.", 15, Color.GRAY)));
            for (Playlist pl : mine) {
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
                row.setOpaque(false);
                row.setMaximumSize(new Dimension(700, 54));
                JLabel nl = text(pl.name + "  (" + Store.songsOf(pl).size() + " songs)", 17, Color.WHITE);
                nl.setPreferredSize(new Dimension(280, 30));
                RoundButton open = new RoundButton("Open", PILL, Color.WHITE, 90, 36);
                open.addActionListener(e -> go("viewall", () -> pageViewAll(pl.name, Store.songsOf(pl), null, pl.name)));
                RoundButton del = new RoundButton("Delete", new Color(190, 50, 50), Color.WHITE, 90, 36);
                del.addActionListener(e -> {
                    if (JOptionPane.showConfirmDialog(this, "Playlist \"" + pl.name + "\" delete karni hai?", "Confirm",
                            JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) { Store.deletePlaylist(pl); refresh(); }
                });
                row.add(nl); row.add(open); row.add(del);
                p.add(left(row));
            }

            p.add(Box.createVerticalStrut(34));
            p.add(left(text("Upload a song", 20, Color.WHITE)));
            p.add(Box.createVerticalStrut(10));
            if (me.role.equals("ARTIST") || me.role.equals("ADMIN")) {
                RoundButton up = new RoundButton("Upload new song", GREEN, Color.BLACK, 200, 42);
                up.addActionListener(e -> go("upload", this::pageUpload));
                p.add(left(up));
            } else {
                p.add(left(text("Gaane upload karne ke liye Artist account chahiye (login screen pe Register -> ARTIST).", 15, Color.GRAY)));
            }
            return scroll(p);
        }

        // ---------- Upload (artist / admin) ----------
        JComponent pageUpload() {
            ScrollPanel p = form();
            JTextField title = new JTextField();
            styleField(title);
            title.setMaximumSize(new Dimension(420, 44));
            final File[] audio = {null};
            final File[] cover = {null};
            JLabel audioLbl = text("Koi file select nahi", 14, Color.GRAY);
            JLabel coverLbl = text("Koi image select nahi (optional)", 14, Color.GRAY);

            RoundButton pickAudio = new RoundButton("Choose audio", PILL, Color.WHITE, 160, 40);
            pickAudio.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                fc.setFileFilter(new FileNameExtensionFilter("Audio (wav, mp3, aiff, au)", "wav", "mp3", "aiff", "au"));
                if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    audio[0] = fc.getSelectedFile();
                    audioLbl.setText(audio[0].getName());
                    audioLbl.setForeground(Color.WHITE);
                }
            });
            RoundButton pickCover = new RoundButton("Choose cover", PILL, Color.WHITE, 160, 40);
            pickCover.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                fc.setFileFilter(new FileNameExtensionFilter("Images (png, jpg)", "png", "jpg", "jpeg"));
                if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    cover[0] = fc.getSelectedFile();
                    coverLbl.setText(cover[0].getName());
                    coverLbl.setForeground(Color.WHITE);
                }
            });
            RoundButton upload = new RoundButton("Upload song", CYAN, Color.BLACK, 200, 46);
            upload.setFont(new Font(FONT, Font.BOLD, 17));
            upload.addActionListener(e -> {
                String t = title.getText().trim();
                if (t.isEmpty() || audio[0] == null) { msg("Song title aur audio file dono zaroori hain."); return; }
                try {
                    int id = Store.nextSongId();
                    File dest = new File(Store.SONG_DIR, "s" + id + ext(audio[0].getName()));
                    Files.copy(audio[0].toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    String cv = "";
                    if (cover[0] != null) {
                        File cd = new File(Store.SONG_DIR, "c" + id + ext(cover[0].getName()));
                        Files.copy(cover[0].toPath(), cd.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        cv = cd.getPath().replace('\\', '/');
                    }
                    Song s = new Song();
                    s.id = id; s.title = t; s.artist = me.name; s.cover = cv;
                    s.file = dest.getPath().replace('\\', '/');
                    s.status = me.role.equals("ADMIN") ? "APPROVED" : "PENDING";
                    Store.songs.add(s);
                    Store.save();
                    msg(me.role.equals("ADMIN") ? "Song publish ho gaya!" : "Song upload ho gaya! Admin approve karega tab sabko dikhega.");
                    refresh();
                } catch (IOException ex) { msg("Upload fail: " + ex.getMessage()); }
            });

            p.add(left(bigTitle("Upload New Song")));
            p.add(Box.createVerticalStrut(22));
            p.add(left(text("Song title", 15, Color.WHITE)));
            p.add(Box.createVerticalStrut(6));
            p.add(left(title));
            p.add(Box.createVerticalStrut(18));
            p.add(left(pickAudio));
            p.add(Box.createVerticalStrut(6));
            p.add(left(audioLbl));
            p.add(Box.createVerticalStrut(16));
            p.add(left(pickCover));
            p.add(Box.createVerticalStrut(6));
            p.add(left(coverLbl));
            p.add(Box.createVerticalStrut(26));
            p.add(left(upload));
            return scroll(p);
        }

        // =================================================================
        //  TABLE PAGES (artist / admin)
        // =================================================================
        JTable darkTable(DefaultTableModel m) {
            JTable t = new JTable(m) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            t.setBackground(CARD);
            t.setForeground(Color.WHITE);
            t.setGridColor(new Color(50, 45, 70));
            t.setRowHeight(38);
            t.setFont(new Font(FONT, Font.PLAIN, 15));
            t.setSelectionBackground(new Color(80, 66, 140));
            t.setSelectionForeground(Color.WHITE);
            t.setShowVerticalLines(false);
            t.setFillsViewportHeight(true);
            t.getTableHeader().setReorderingAllowed(false);
            DefaultTableCellRenderer hr = new DefaultTableCellRenderer();
            hr.setBackground(new Color(20, 18, 32));
            hr.setForeground(CYAN);
            hr.setFont(new Font(FONT, Font.BOLD, 15));
            hr.setBorder(new EmptyBorder(0, 8, 0, 8));
            hr.setHorizontalAlignment(SwingConstants.LEFT);
            t.getTableHeader().setDefaultRenderer(hr);
            t.getTableHeader().setPreferredSize(new Dimension(0, 38));
            return t;
        }

        JPanel tablePage(String title, JTable t, JButton... btns) {
            JPanel p = new JPanel(new BorderLayout(0, 14));
            p.setBackground(BG);
            p.setBorder(new EmptyBorder(20, 24, 20, 24));
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(bigTitle(title), BorderLayout.WEST);
            JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            bp.setOpaque(false);
            for (JButton b : btns) bp.add(b);
            top.add(bp, BorderLayout.EAST);
            JScrollPane sp = new JScrollPane(t);
            sp.setBorder(new LineBorder(LINE));
            sp.getViewport().setBackground(CARD);
            sp.getVerticalScrollBar().setUI(new DarkScrollBarUI());
            p.add(top, BorderLayout.NORTH);
            p.add(sp, BorderLayout.CENTER);
            return p;
        }

        RoundButton tb(String text, Color bg, int w) {
            RoundButton b = new RoundButton(text, bg, Color.WHITE, w, 38);
            b.setFont(new Font(FONT, Font.PLAIN, 14));
            return b;
        }

        int sel(JTable t) {
            int r = t.getSelectedRow();
            if (r < 0) msg("Pehle table me se ek row select karo.");
            return r;
        }

        void removeSongSafely(Song s) {
            if (nowPlaying == s) resetPlayer();
            queue.remove(s);
            recent.remove(s);
            Store.deleteSong(s);
        }

        JComponent pageMySongs() {
            List<Song> list = Store.byArtist(me.name);
            DefaultTableModel m = new DefaultTableModel(new Object[]{"Title", "Status", "Plays"}, 0);
            for (Song s : list) m.addRow(new Object[]{s.title, s.status, s.plays});
            JTable t = darkTable(m);
            RoundButton upl = tb("Upload", new Color(30, 150, 80), 100);
            upl.addActionListener(e -> go("upload", this::pageUpload));
            RoundButton play = tb("Play", PILL, 90);
            play.addActionListener(e -> { int r = sel(t); if (r >= 0) playSong(list.get(r), Collections.singletonList(list.get(r))); });
            RoundButton rename = tb("Rename", new Color(60, 90, 160), 100);
            rename.addActionListener(e -> {
                int r = sel(t);
                if (r < 0) return;
                String nt = JOptionPane.showInputDialog(this, "Naya title:", list.get(r).title);
                if (nt != null && !nt.trim().isEmpty()) { list.get(r).title = nt.trim(); Store.save(); refresh(); }
            });
            RoundButton del = tb("Delete", new Color(190, 50, 50), 100);
            del.addActionListener(e -> {
                int r = sel(t);
                if (r < 0) return;
                if (JOptionPane.showConfirmDialog(this, "\"" + list.get(r).title + "\" delete karna hai?", "Confirm",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    removeSongSafely(list.get(r));
                    refresh();
                }
            });
            return tablePage("My Songs", t, upl, play, rename, del);
        }

        JComponent pageAdminSongs() {
            List<Song> list = new ArrayList<>(Store.songs);
            DefaultTableModel m = new DefaultTableModel(new Object[]{"ID", "Title", "Artist", "Status", "Plays"}, 0);
            for (Song s : list) m.addRow(new Object[]{s.id, s.title, Store.artistName(s), s.status, s.plays});
            JTable t = darkTable(m);
            RoundButton approve = tb("Approve", new Color(30, 150, 80), 100);
            approve.addActionListener(e -> {
                int r = sel(t);
                if (r < 0) return;
                list.get(r).status = "APPROVED"; Store.save(); refresh();
            });
            RoundButton reject = tb("Reject", new Color(200, 120, 30), 90);
            reject.addActionListener(e -> {
                int r = sel(t);
                if (r < 0) return;
                Song s = list.get(r);
                s.status = "REJECTED";
                if (nowPlaying == s) resetPlayer();
                Store.save(); refresh();
            });
            RoundButton del = tb("Delete", new Color(190, 50, 50), 90);
            del.addActionListener(e -> {
                int r = sel(t);
                if (r < 0) return;
                if (JOptionPane.showConfirmDialog(this, "\"" + list.get(r).title + "\" delete karna hai?", "Confirm",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    removeSongSafely(list.get(r));
                    refresh();
                }
            });
            RoundButton play = tb("Play", PILL, 80);
            play.addActionListener(e -> { int r = sel(t); if (r >= 0) playSong(list.get(r), Collections.singletonList(list.get(r))); });
            return tablePage("Manage Songs", t, play, approve, reject, del);
        }

        JComponent pageAdminUsers() {
            List<User> list = new ArrayList<>(Store.users);
            DefaultTableModel m = new DefaultTableModel(new Object[]{"Username", "Name", "Role", "Status", "Premium"}, 0);
            for (User u : list) m.addRow(new Object[]{u.name, u.display, u.role, u.blocked ? "BLOCKED" : "ACTIVE",
                    u.isPremium() ? "till " + dateStr(u.premiumUntil) : "-"});
            JTable t = darkTable(m);
            RoundButton add = tb("Add User", new Color(30, 150, 80), 100);
            add.addActionListener(e -> { if (userDialog(this, true)) refresh(); });
            RoundButton block = tb("Block/Unblock", new Color(200, 120, 30), 130);
            block.addActionListener(e -> {
                int r = sel(t);
                if (r < 0) return;
                User u = list.get(r);
                if (u.name.equals(me.name)) { msg("Apne aap ko block nahi kar sakte."); return; }
                u.blocked = !u.blocked; Store.save(); refresh();
            });
            RoundButton prem = tb("+30d Premium", new Color(170, 130, 20), 130);
            prem.addActionListener(e -> {
                int r = sel(t);
                if (r < 0) return;
                User u = list.get(r);
                u.premiumUntil = Math.max(System.currentTimeMillis(), u.premiumUntil) + 30L * 86400000L;
                Store.save();
                updateBadge();
                refresh();
            });
            RoundButton reset = tb("Reset Password", new Color(60, 90, 160), 140);
            reset.addActionListener(e -> {
                int r = sel(t);
                if (r < 0) return;
                User u = list.get(r);
                String pw = JOptionPane.showInputDialog(this, "New password for " + u.name + " (min 4 chars):");
                if (pw == null) return;
                if (pw.length() < 4) { msg("Password kam se kam 4 characters ka ho."); return; }
                u.hash = Store.hash(pw); Store.save(); msg("Password change ho gaya.");
            });
            RoundButton del = tb("Delete", new Color(190, 50, 50), 90);
            del.addActionListener(e -> {
                int r = sel(t);
                if (r < 0) return;
                User u = list.get(r);
                if (u.name.equals(me.name)) { msg("Apne aap ko delete nahi kar sakte."); return; }
                if (JOptionPane.showConfirmDialog(this, u.name + " aur uske saare songs delete honge. Pakka?", "Confirm",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    if (nowPlaying != null && nowPlaying.artist.equalsIgnoreCase(u.name)) resetPlayer();
                    queue.removeIf(s -> s.artist.equalsIgnoreCase(u.name));
                    recent.removeIf(s -> s.artist.equalsIgnoreCase(u.name));
                    if (chatPartner == u) chatPartner = null;
                    Store.deleteUser(u);
                    refresh();
                }
            });
            return tablePage("Manage Users", t, add, block, prem, reset, del);
        }

        // =================================================================
        //  PAGE: Premium
        // =================================================================
        JComponent pagePremium() {
            ScrollPanel p = form();
            p.add(left(bigTitle("Premium")));
            p.add(Box.createVerticalStrut(8));
            p.add(left(text(me.isPremium() ? "Aapka Premium " + dateStr(me.premiumUntil) + " tak active hai."
                    : "Aap abhi Free plan pe ho.", 17, me.isPremium() ? GOLD : Color.LIGHT_GRAY)));
            p.add(Box.createVerticalStrut(6));
            p.add(left(text("Note: ye demo payment hai - asli paisa nahi katta.", 13, Color.GRAY)));
            p.add(Box.createVerticalStrut(26));

            JPanel plans = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 0));
            plans.setOpaque(false);
            plans.setMaximumSize(new Dimension(Integer.MAX_VALUE, 400));
            plans.add(planCard("Free", "Rs. 0", "hamesha ke liye",
                    new String[]{"Saare gaane stream karo", "Search, likes, playlists", "Users ke saath chat"},
                    me.isPremium() ? "Cancel Premium" : "Current plan",
                    me.isPremium() ? new Color(190, 50, 50) : PILL,
                    me.isPremium() ? () -> {
                        if (JOptionPane.showConfirmDialog(this, "Premium cancel karna hai?", "Confirm",
                                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                            me.premiumUntil = 0; Store.save(); updateBadge(); refresh();
                        }
                    } : null, false));
            plans.add(planCard("Premium - Monthly", "Rs. 99", "per month (demo)",
                    new String[]{"Free ke saare features", "Songs download karo", "Gold PREMIUM badge"},
                    me.isPremium() ? "Extend 30 days" : "Subscribe", GOLD,
                    () -> subscribe("Monthly", 30, "Rs. 99"), true));
            plans.add(planCard("Premium - Yearly", "Rs. 999", "per year (demo)",
                    new String[]{"Monthly ke saare features", "2 mahine free (save ~16%)", "Best value"},
                    me.isPremium() ? "Extend 1 year" : "Subscribe", GOLD,
                    () -> subscribe("Yearly", 365, "Rs. 999"), false));
            p.add(left(plans));
            return scroll(p);
        }

        JPanel planCard(String name, String price, String per, String[] feats, String btnText, Color btnColor,
                        Runnable action, boolean highlight) {
            JPanel c = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = aa(g);
                    g2.setColor(CARD);
                    g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 22, 22);
                    if (highlight) {
                        g2.setColor(GOLD);
                        g2.setStroke(new BasicStroke(2f));
                        g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 22, 22);
                    }
                    g2.dispose();
                }
            };
            c.setOpaque(false);
            c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
            c.setBorder(new EmptyBorder(24, 24, 24, 24));
            c.setPreferredSize(new Dimension(280, 380));
            c.add(left(text(name, 22, Color.WHITE)));
            c.add(Box.createVerticalStrut(10));
            JLabel pr = text(price, 34, GOLD);
            pr.setFont(new Font(FONT, Font.BOLD, 34));
            c.add(left(pr));
            c.add(left(text(per, 14, Color.GRAY)));
            c.add(Box.createVerticalStrut(20));
            for (String f : feats) {
                c.add(left(text("+  " + f, 15, Color.WHITE)));
                c.add(Box.createVerticalStrut(8));
            }
            c.add(Box.createVerticalGlue());
            RoundButton b = new RoundButton(btnText, btnColor, btnColor.equals(PILL) ? Color.LIGHT_GRAY : Color.BLACK, 230, 44);
            if (btnColor.equals(new Color(190, 50, 50))) b.fg = Color.WHITE;
            b.setFont(new Font(FONT, Font.BOLD, 16));
            b.setMaximumSize(new Dimension(230, 44));
            if (action != null) b.addActionListener(e -> action.run());
            c.add(left(b));
            return c;
        }

        void subscribe(String plan, int days, String price) {
            int r = JOptionPane.showConfirmDialog(this, "DEMO payment: " + plan + " (" + price + ")\n"
                    + "Ye sirf demo hai, asli paisa nahi katega.\nPremium activate karein?", "Premium",
                    JOptionPane.YES_NO_OPTION);
            if (r != JOptionPane.YES_OPTION) return;
            long base = Math.max(System.currentTimeMillis(), me.premiumUntil);
            me.premiumUntil = base + days * 86400000L;
            Store.save();
            updateBadge();
            refresh();
        }

        // =================================================================
        //  PAGE: Chat
        // =================================================================
        class ContactCell extends JPanel implements ListCellRenderer<User> {
            User u;
            boolean sel;
            int unread;

            ContactCell() { setOpaque(false); setPreferredSize(new Dimension(260, 64)); }

            @Override public Component getListCellRendererComponent(JList<? extends User> l, User v, int i, boolean s, boolean f) {
                u = v; sel = s; unread = Store.unread(me.name, v.name);
                return this;
            }

            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = aa(g);
                if (sel) { g2.setColor(new Color(80, 66, 140)); g2.fillRoundRect(6, 4, getWidth() - 12, getHeight() - 8, 14, 14); }
                Color ac = Color.getHSBColor((Math.abs(u.name.hashCode()) % 360) / 360f, .55f, .85f);
                g2.setColor(ac);
                g2.fillOval(16, 12, 40, 40);
                g2.setColor(Color.BLACK);
                g2.setFont(new Font(FONT, Font.BOLD, 18));
                String ini = u.display.isEmpty() ? "?" : u.display.substring(0, 1).toUpperCase();
                g2.drawString(ini, 36 - g2.getFontMetrics().stringWidth(ini) / 2, 38);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font(FONT, Font.BOLD, 15));
                g2.drawString(u.display, 68, 29);
                g2.setColor(u.role.equals("ADMIN") ? new Color(255, 110, 110) : u.role.equals("ARTIST") ? new Color(255, 180, 70) : CYAN);
                g2.setFont(new Font(FONT, Font.PLAIN, 12));
                g2.drawString(u.role + (u.isPremium() ? "  *PREMIUM" : ""), 68, 47);
                if (unread > 0) {
                    g2.setColor(new Color(235, 60, 80));
                    g2.fillOval(getWidth() - 44, 21, 24, 24);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font(FONT, Font.BOLD, 12));
                    String t = unread > 99 ? "99+" : String.valueOf(unread);
                    g2.drawString(t, getWidth() - 32 - g2.getFontMetrics().stringWidth(t) / 2, 38);
                }
                g2.dispose();
            }
        }

        JComponent pageChat() {
            Store.reloadChat(true);
            JPanel root = new JPanel(new BorderLayout(16, 0));
            root.setBackground(BG);
            root.setBorder(new EmptyBorder(18, 22, 18, 22));

            DefaultListModel<User> model = new DefaultListModel<>();
            List<User> others = new ArrayList<>();
            for (User u : Store.users) if (!u.name.equals(me.name)) others.add(u);
            others.sort((a, b) -> a.display.compareToIgnoreCase(b.display));
            for (User u : others) model.addElement(u);
            chatList = new JList<>(model);
            chatList.setCellRenderer(new ContactCell());
            chatList.setFixedCellHeight(64);
            chatList.setBackground(new Color(20, 18, 32));
            chatList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            chatList.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            chatList.addListSelectionListener((ListSelectionEvent e) -> {
                if (e.getValueIsAdjusting()) return;
                User u = chatList.getSelectedValue();
                if (u != null) { chatPartner = u; renderChat(); chatList.repaint(); }
            });
            JScrollPane cs = new JScrollPane(chatList);
            cs.setBorder(new LineBorder(LINE));
            cs.getViewport().setBackground(new Color(20, 18, 32));
            cs.getVerticalScrollBar().setUI(new DarkScrollBarUI());
            cs.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
            cs.setPreferredSize(new Dimension(272, 0));

            JPanel contacts = new JPanel(new BorderLayout(0, 10));
            contacts.setOpaque(false);
            contacts.add(bigTitle("Chat"), BorderLayout.NORTH);
            contacts.add(cs, BorderLayout.CENTER);

            chatHeader = text("Select a contact", 18, Color.WHITE);
            chatHeader.setFont(new Font(FONT, Font.BOLD, 19));
            chatHeader.setBorder(new EmptyBorder(8, 4, 12, 4));
            chatMsgs = new ScrollPanel();
            chatMsgs.setLayout(new BoxLayout(chatMsgs, BoxLayout.Y_AXIS));
            chatMsgs.setBackground(new Color(12, 11, 20));
            chatMsgs.setBorder(new EmptyBorder(10, 6, 10, 6));
            chatScroll = new JScrollPane(chatMsgs, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            chatScroll.setBorder(new LineBorder(LINE));
            chatScroll.getViewport().setBackground(new Color(12, 11, 20));
            chatScroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
            chatScroll.getVerticalScrollBar().setUI(new DarkScrollBarUI());
            chatScroll.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
            chatScroll.getVerticalScrollBar().setUnitIncrement(24);

            chatInput = new JTextField();
            styleField(chatInput);
            RoundButton send = new RoundButton("Send", CYAN, Color.BLACK, 100, 44);
            send.setFont(new Font(FONT, Font.BOLD, 16));
            Runnable doSend = () -> {
                String t = chatInput.getText().trim();
                if (chatPartner == null) { msg("Pehle left se kisi contact ko select karo."); return; }
                if (t.isEmpty()) return;
                Store.sendMsg(me.name, chatPartner.name, t);
                chatInput.setText("");
                renderChat();
            };
            chatInput.addActionListener(e -> doSend.run());
            send.addActionListener(e -> doSend.run());
            JPanel inputRow = new JPanel(new BorderLayout(10, 0));
            inputRow.setOpaque(false);
            inputRow.setBorder(new EmptyBorder(12, 0, 0, 0));
            inputRow.add(chatInput, BorderLayout.CENTER);
            inputRow.add(send, BorderLayout.EAST);

            JPanel right = new JPanel(new BorderLayout());
            right.setOpaque(false);
            right.add(chatHeader, BorderLayout.NORTH);
            right.add(chatScroll, BorderLayout.CENTER);
            right.add(inputRow, BorderLayout.SOUTH);

            root.add(contacts, BorderLayout.WEST);
            root.add(right, BorderLayout.CENTER);

            if (chatPartner != null && model.contains(chatPartner)) chatList.setSelectedValue(chatPartner, true);
            else { chatPartner = null; renderChat(); }
            return root;
        }

        String chatTime(long ts) {
            Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
            b.setTimeInMillis(ts);
            boolean today = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
            return new SimpleDateFormat(today ? "HH:mm" : "dd MMM, HH:mm").format(new Date(ts));
        }

        void renderChat() {
            if (chatMsgs == null) return;
            chatMsgs.removeAll();
            if (chatPartner == null) {
                chatHeader.setText("Select a contact");
                chatMsgs.add(left(emptyChat("Left side se kisi contact ko select karo.")));
            } else {
                chatHeader.setText(chatPartner.display + "   (" + chatPartner.role + ")");
                List<Msg> conv = Store.conversation(me.name, chatPartner.name);
                chatShown = conv.size();
                if (conv.isEmpty()) chatMsgs.add(left(emptyChat("Abhi koi message nahi. Pehla message bhejo!")));
                for (Msg m : conv) {
                    boolean mine = m.from.equalsIgnoreCase(me.name);
                    JPanel row = new JPanel(new BorderLayout());
                    row.setOpaque(false);
                    row.setBorder(new EmptyBorder(3, 8, 3, 8));
                    row.add(new Bubble(m.text, chatTime(m.ts), mine), mine ? BorderLayout.EAST : BorderLayout.WEST);
                    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
                    row.setAlignmentX(Component.LEFT_ALIGNMENT);
                    chatMsgs.add(row);
                }
                Store.markRead(me.name, chatPartner.name);
            }
            chatMsgs.revalidate();
            chatMsgs.repaint();
            SwingUtilities.invokeLater(() -> {
                JScrollBar b = chatScroll.getVerticalScrollBar();
                b.setValue(b.getMaximum());
            });
        }

        JLabel emptyChat(String t) {
            JLabel l = text(t, 15, Color.GRAY);
            l.setBorder(new EmptyBorder(20, 12, 0, 0));
            return l;
        }

        // =================================================================
        //  PAGE: Profile
        // =================================================================
        JComponent pageProfile() {
            ScrollPanel p = form();
            p.add(left(bigTitle(me.display)));
            p.add(Box.createVerticalStrut(8));
            p.add(left(text("@" + me.name + "   |   " + me.role + (me.isPremium() ? "   |   PREMIUM till " + dateStr(me.premiumUntil) : ""),
                    16, me.isPremium() ? GOLD : CYAN)));
            p.add(Box.createVerticalStrut(18));
            if (me.role.equals("ARTIST")) {
                List<Song> mine = Store.byArtist(me.name);
                int plays = 0;
                for (Song s : mine) plays += s.plays;
                p.add(left(text("Total songs: " + mine.size() + "     Total plays: " + plays, 17, Color.WHITE)));
            } else if (me.role.equals("ADMIN")) {
                long pending = Store.songs.stream().filter(s -> "PENDING".equals(s.status)).count();
                p.add(left(text("Users: " + Store.users.size() + "     Songs: " + Store.songs.size()
                        + "     Pending approval: " + pending, 17, Color.WHITE)));
            }
            p.add(Box.createVerticalStrut(6));
            p.add(left(text("Liked songs: " + Store.favSongs(me.name).size() + "     Playlists: " + Store.playlistsOf(me.name).size(),
                    17, Color.WHITE)));
            p.add(Box.createVerticalStrut(30));
            p.add(left(text("Change password", 20, Color.WHITE)));
            p.add(Box.createVerticalStrut(10));
            JPasswordField oldPw = new JPasswordField(), newPw = new JPasswordField();
            styleField(oldPw); styleField(newPw);
            oldPw.setMaximumSize(new Dimension(360, 44));
            newPw.setMaximumSize(new Dimension(360, 44));
            p.add(left(text("Old password", 14, Color.LIGHT_GRAY)));
            p.add(Box.createVerticalStrut(4));
            p.add(left(oldPw));
            p.add(Box.createVerticalStrut(10));
            p.add(left(text("New password", 14, Color.LIGHT_GRAY)));
            p.add(Box.createVerticalStrut(4));
            p.add(left(newPw));
            p.add(Box.createVerticalStrut(14));
            RoundButton save = new RoundButton("Update password", CYAN, Color.BLACK, 200, 42);
            save.addActionListener(e -> {
                if (!me.hash.equals(Store.hash(new String(oldPw.getPassword())))) { msg("Purana password galat hai."); return; }
                String np = new String(newPw.getPassword());
                if (np.length() < 4) { msg("Naya password kam se kam 4 characters ka ho."); return; }
                me.hash = Store.hash(np);
                Store.save();
                oldPw.setText(""); newPw.setText("");
                msg("Password update ho gaya.");
            });
            p.add(left(save));
            if (me.role.equals("ADMIN")) {
                p.add(Box.createVerticalStrut(30));
                p.add(left(text("Streaming API key (Jamendo client_id)", 20, Color.WHITE)));
                p.add(Box.createVerticalStrut(10));
                JPasswordField kf = new JPasswordField(Config.apiKey());
                styleField(kf);
                kf.setMaximumSize(new Dimension(360, 44));
                p.add(left(kf));
                p.add(Box.createVerticalStrut(12));
                JPanel kr = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
                kr.setOpaque(false);
                kr.setMaximumSize(new Dimension(360, 44));
                RoundButton ks = new RoundButton("Save key", CYAN, Color.BLACK, 120, 40);
                ks.addActionListener(e -> {
                    Config.setApiKey(new String(kf.getPassword()));
                    onlineCache.clear();
                    msg("API key save ho gayi. Home pe online tracks load honge.");
                });
                RoundButton kt = new RoundButton("Test key", PILL, Color.WHITE, 120, 40);
                kt.addActionListener(e -> {
                    String k = new String(kf.getPassword()).trim();
                    if (k.isEmpty()) { msg("Pehle API key daalo."); return; }
                    new SwingWorker<Integer, Void>() {
                        @Override protected Integer doInBackground() throws Exception { return Api.tracks("", k, 3).size(); }
                        @Override protected void done() {
                            try { msg("Key sahi hai! " + get() + " tracks mile."); }
                            catch (Exception ex) {
                                Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                                msg("Key test fail: " + c.getMessage());
                            }
                        }
                    }.execute();
                });
                kr.add(ks);
                kr.add(kt);
                p.add(left(kr));
            }
            p.add(Box.createVerticalStrut(34));
            RoundButton logout = new RoundButton("Logout", new Color(190, 50, 50), Color.WHITE, 200, 42);
            logout.addActionListener(e -> {
                ticker.stop();
                stopClip();
                dispose();
                new LoginFrame().setVisible(true);
            });
            p.add(left(logout));
            return scroll(p);
        }

        // =================================================================
        //  PLAYER
        // =================================================================
        JComponent buildPlayer() {
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(BG);
            bar.setPreferredSize(new Dimension(0, 96));
            bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LINE));

            JPanel lft = new JPanel(null);
            lft.setOpaque(false);
            lft.setPreferredSize(new Dimension(360, 96));
            art.setForeground(Color.WHITE);
            art.setFont(new Font(FONT, Font.PLAIN, 12));
            art.setBounds(20, 18, 62, 60);
            songTitle.setFont(new Font(FONT, Font.BOLD, 19));
            songTitle.setForeground(Color.WHITE);
            songTitle.setBounds(96, 24, 255, 26);
            songArtist.setFont(new Font(FONT, Font.PLAIN, 15));
            songArtist.setForeground(new Color(200, 200, 200));
            songArtist.setBounds(96, 50, 255, 22);
            lft.add(art); lft.add(songTitle); lft.add(songArtist);

            JPanel center = new JPanel();
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            center.setOpaque(false);

            JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 22, 0));
            controls.setOpaque(false);
            shuffleBtn = new IconButton("shuffle", 36, null, Color.WHITE);
            IconButton prev = new IconButton("prev", 36, null, Color.WHITE);
            playBtn = new IconButton("play", 44, Color.WHITE, Color.BLACK);
            IconButton next = new IconButton("next", 36, null, Color.WHITE);
            repeatBtn = new IconButton("repeat", 36, null, Color.WHITE);
            shuffleBtn.addActionListener(e -> { shuffle = !shuffle; shuffleBtn.setFg(shuffle ? GREEN : Color.WHITE); });
            repeatBtn.addActionListener(e -> { repeat = !repeat; repeatBtn.setFg(repeat ? GREEN : Color.WHITE); });
            playBtn.addActionListener(e -> togglePlay());
            prev.addActionListener(e -> prevTrack());
            next.addActionListener(e -> nextTrack(false));
            controls.add(shuffleBtn); controls.add(prev); controls.add(playBtn); controls.add(next); controls.add(repeatBtn);
            controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));

            JPanel prog = new JPanel(new BorderLayout(10, 0));
            prog.setOpaque(false);
            timeNow.setForeground(Color.WHITE); timeEnd.setForeground(Color.WHITE);
            timeNow.setFont(new Font(FONT, Font.PLAIN, 14)); timeEnd.setFont(new Font(FONT, Font.PLAIN, 14));
            progress.onSeek = v -> {
                if (clip != null) {
                    clip.setMicrosecondPosition((long) (v * clip.getMicrosecondLength()));
                    updateProgress();
                }
            };
            prog.add(timeNow, BorderLayout.WEST);
            prog.add(progress, BorderLayout.CENTER);
            prog.add(timeEnd, BorderLayout.EAST);
            prog.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

            center.add(Box.createVerticalStrut(4));
            center.add(controls);
            center.add(prog);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 28));
            right.setOpaque(false);
            right.setPreferredSize(new Dimension(300, 96));
            muteBtn = new IconButton("volume", 32, null, Color.WHITE);
            muteBtn.addActionListener(e -> {
                muted = !muted;
                muteBtn.setType(muted ? "mute" : "volume");
                applyVolume();
            });
            volBar.setPreferredSize(new Dimension(80, 32));
            volBar.fill = Color.WHITE;
            volBar.setValue(volume);
            volBar.onSeek = v -> {
                volume = v;
                if (muted && v > 0) { muted = false; muteBtn.setType("volume"); }
                applyVolume();
            };
            IconButton full = new IconButton("fullscreen", 32, null, Color.WHITE);
            full.addActionListener(e -> toggleFullscreen());
            IconButton hp = new IconButton("headphones", 32, null, Color.WHITE);
            hp.addActionListener(e -> showQueue(hp));
            right.add(muteBtn);
            right.add(volBar);
            right.add(full);
            right.add(hp);

            bar.add(lft, BorderLayout.WEST);
            bar.add(center, BorderLayout.CENTER);
            bar.add(right, BorderLayout.EAST);
            return bar;
        }

        void showQueue(Component inv) {
            JPopupMenu m = new JPopupMenu();
            if (queue.isEmpty()) m.add(mi("Queue khali hai - koi gaana play karo", () -> { }));
            for (int i = 0; i < queue.size() && i < 15; i++) {
                final int idx = i;
                Song s = queue.get(i);
                m.add(mi((i == qIndex ? ">  " : "    ") + s.title + "  -  " + Store.artistName(s), () -> { qIndex = idx; startCurrent(); }));
            }
            m.show(inv, 0, -m.getPreferredSize().height);
        }

        Clip openClip(File f) throws Exception {
            AudioInputStream in = AudioSystem.getAudioInputStream(f);
            AudioFormat base = in.getFormat();
            if (base.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                AudioFormat dec = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.getSampleRate(), 16,
                        base.getChannels(), base.getChannels() * 2, base.getSampleRate(), false);
                in = AudioSystem.getAudioInputStream(dec, in);
            }
            Clip c = AudioSystem.getClip();
            c.open(in);
            return c;
        }

        void playSong(Song s, List<Song> q) {
            queue = new ArrayList<>(q);
            qIndex = queue.indexOf(s);
            if (qIndex < 0) { queue.add(s); qIndex = queue.size() - 1; }
            startCurrent();
        }

        ImageIcon scaled(BufferedImage im) {
            return new ImageIcon(im.getScaledInstance(60, 60, Image.SCALE_SMOOTH));
        }

        void startCurrent() {
            stopClip();
            final int token = ++playToken;
            final Song s = queue.get(qIndex);
            nowPlaying = s;
            playing = false;
            songTitle.setText(s.title);
            songArtist.setText(Store.artistName(s));
            art.setText("");
            art.setIcon(scaled(Art.forSong(s, () -> {
                if (nowPlaying == s) art.setIcon(scaled(Art.forSong(s)));
            })));
            progress.setValue(0);
            timeNow.setText("0:00");
            timeEnd.setText("0:00");
            playBtn.setType("play");
            holder.repaint();

            if (!s.online) { playFile(s, new File(s.file)); return; }

            final File cache = new File("data/cache/jam_" + Math.abs(s.id) + ".mp3");
            if (cache.exists() && cache.length() > 0) { playFile(s, cache); return; }
            songTitle.setText(s.title + "  (loading...)");
            new SwingWorker<File, Void>() {
                @Override protected File doInBackground() throws Exception { return Api.download(s.streamUrl, cache); }
                @Override protected void done() {
                    if (token != playToken) return;
                    songTitle.setText(s.title);
                    try { playFile(s, get()); }
                    catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        msg("Stream load nahi hua: " + c.getMessage());
                    }
                }
            }.execute();
        }

        void playFile(Song s, File f) {
            try {
                if (!f.exists()) throw new IOException("Audio file nahi mili: " + f.getPath());
                clip = openClip(f);
                applyVolume();
                clip.start();
                startedAt = System.currentTimeMillis();
                playing = true;
                timeEnd.setText(fmt((int) (clip.getMicrosecondLength() / 1_000_000)));
                if (!s.online) { s.plays++; Store.save(); }
                recent.remove(s);
                recent.add(0, s);
                if (recent.size() > 20) recent.remove(recent.size() - 1);
            } catch (UnsupportedAudioFileException ex) {
                playing = false;
                msg("Ye audio format support nahi hai.\nMP3 / online streaming ke liye mp3spi + jlayer + tritonus-share jar classpath me add karo.");
            } catch (Exception ex) {
                playing = false;
                msg("Gaana play nahi ho paya: " + ex.getMessage());
            }
            playBtn.setType(playing ? "pause" : "play");
            holder.repaint();
        }

        void togglePlay() {
            if (clip == null) {
                if (nowPlaying != null && qIndex >= 0 && qIndex < queue.size()) { startCurrent(); return; }
                List<Song> ap = Store.approved();
                if (!ap.isEmpty()) playSong(ap.get(0), ap);
                return;
            }
            if (playing) {
                clip.stop();
                playing = false;
            } else {
                clip.start();
                startedAt = System.currentTimeMillis();
                playing = true;
            }
            playBtn.setType(playing ? "pause" : "play");
        }

        void nextTrack(boolean auto) {
            if (queue.isEmpty()) return;
            if (shuffle && queue.size() > 1) {
                int n;
                do { n = rnd.nextInt(queue.size()); } while (n == qIndex);
                qIndex = n;
            } else {
                qIndex++;
                if (qIndex >= queue.size()) {
                    if (repeat || !auto) qIndex = 0;
                    else { qIndex = queue.size() - 1; stopClip(); playing = false; playBtn.setType("play"); updateProgress(); return; }
                }
            }
            startCurrent();
        }

        void prevTrack() {
            if (queue.isEmpty()) return;
            if (clip != null && clip.getMicrosecondPosition() > 3_000_000L) {
                clip.setMicrosecondPosition(0);
                updateProgress();
                return;
            }
            qIndex = (qIndex - 1 + queue.size()) % queue.size();
            startCurrent();
        }

        void stopClip() {
            if (clip != null) {
                try { clip.stop(); clip.close(); } catch (Exception ignored) { }
                clip = null;
            }
        }

        void resetPlayer() {
            playToken++;
            stopClip();
            playing = false;
            nowPlaying = null;
            songTitle.setText("Koi gaana select nahi");
            songArtist.setText("-");
            art.setIcon(null);
            art.setText("Album Art");
            timeEnd.setText("0:00");
            playBtn.setType("play");
            updateProgress();
        }

        void applyVolume() {
            if (clip != null && clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl c = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float db;
                if (muted || volume < 0.005) db = c.getMinimum();
                else db = (float) Math.max(c.getMinimum(), Math.min(c.getMaximum(), 20 * Math.log10(volume)));
                c.setValue(db);
            }
        }

        void tick() {
            if (clip != null) {
                updateProgress();
                if (playing && !clip.isRunning() && System.currentTimeMillis() - startedAt > 700) nextTrack(true);
            }
            if (++pollCount % 6 == 0) {   // ~1.5 sec: naye chat messages check
                Store.reloadChat(false);
                int tot = Store.totalUnread(me.name);
                if (tot != chatBadge) { chatBadge = tot; sidebar.repaint(); }
                if ("chat".equals(activeKey) && chatList != null) {
                    if (chatPartner != null && Store.conversation(me.name, chatPartner.name).size() != chatShown) renderChat();
                    chatList.repaint();
                }
            }
        }

        void updateProgress() {
            if (clip == null) { progress.setValue(0); timeNow.setText("0:00"); return; }
            long pos = clip.getMicrosecondPosition(), len = clip.getMicrosecondLength();
            progress.setValue(len > 0 ? Math.min(1.0, pos / (double) len) : 0);
            timeNow.setText(fmt((int) (pos / 1_000_000)));
        }

        void toggleFullscreen() {
            dispose();
            setUndecorated(!fullscreen);
            setExtendedState(fullscreen ? JFrame.NORMAL : JFrame.MAXIMIZED_BOTH);
            fullscreen = !fullscreen;
            setVisible(true);
        }
    }
}