import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.Iterator;
import javax.sound.sampled.*;

public class Game extends JPanel {

    // --- SYSTEM CONSTANTS ---
    private final int WIDTH = 1024;
    private final int HEIGHT = 768;
    private final int TARGET_FPS = 60;

    // --- COLORS ---
    private final Color HUD_COLOR = new Color(0, 255, 200);
    private final Color DANGER_COLOR = new Color(255, 30, 60);
    private final Color DATA_COLOR = new Color(255, 200, 0);
    private final Color BG_COLOR = new Color(10, 15, 20);

    // --- GAME METRICS ---
    private int state = 0; // 0=BOOT, 1=PLAY, 2=FAILURE
    private long score = 0;
    private double multiplier = 1.0;
    private int grazeCount = 0;
    private double energy = 100.0;
    private long framesAlive = 0;

    // --- ENTITIES ---
    private double playerX = WIDTH / 2.0, playerY = HEIGHT / 2.0;
    private double targetX = WIDTH / 2.0, targetY = HEIGHT / 2.0;
    private ArrayList<DataNode> nodes = new ArrayList<>();
    private ArrayList<Virus> viruses = new ArrayList<>();
    private ArrayList<Particle> particles = new ArrayList<>();
    private ArrayList<FloatingText> floaters = new ArrayList<>();

    // --- ULTRA-FAST BOOT SCREEN ---
    private String[] bootLines = {
            "SYS.OP.AURA // ONLINE",
            "> CLICK TO ENGAGE OVERRIDE"
    };
    private int bootLineIndex = 0;
    private int bootCharIndex = 0;

    public Game() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(BG_COLOR);
        setFocusable(true);

        // Hide mouse cursor for native feel
        setCursor(Toolkit.getDefaultToolkit().createCustomCursor(
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                new Point(0, 0), "blank"));

        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) { targetX = e.getX(); targetY = e.getY(); }
            public void mouseDragged(MouseEvent e) { mouseMoved(e); }
        });

        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (state == 0) startGame(); // Instant skip available
                else if (state == 2) state = 0; // Reboot
            }
        });

        // Main Game Loop
        new Thread(() -> {
            long lastTime = System.nanoTime();
            double nsPerTick = 1000000000.0 / TARGET_FPS;
            double delta = 0;
            while (true) {
                long now = System.nanoTime();
                delta += (now - lastTime) / nsPerTick;
                lastTime = now;
                while (delta >= 1) {
                    tick();
                    delta--;
                }
                repaint();
                try { Thread.sleep(2); } catch (Exception ex) {}
            }
        }).start();

        AudioEngine.init();
    }

    private void startGame() {
        score = 0; multiplier = 1.0; grazeCount = 0; energy = 100.0; framesAlive = 0;
        nodes.clear(); viruses.clear(); particles.clear(); floaters.clear();
        playerX = WIDTH / 2.0; playerY = HEIGHT / 2.0;
        state = 1;
        spawnNode();
        AudioEngine.playSound("boot");
    }

    private void tick() {
        if (state == 0) {
            // Super fast typing effect
            if (bootLineIndex < bootLines.length) {
                bootCharIndex += 2; // Type 2 chars per frame
                if (bootCharIndex > bootLines[bootLineIndex].length()) {
                    bootCharIndex = 0;
                    bootLineIndex++;
                }
            }
        } else if (state == 1) {
            framesAlive++;

            // Smooth player movement
            playerX += (targetX - playerX) * 0.15;
            playerY += (targetY - playerY) * 0.15;

            // Multiplier decay
            if (multiplier > 1.0) multiplier -= 0.002;

            // INCREASED ENERGY DECAY (Inevitable loss mechanic)
            // Starts normal, but drains extremely fast the longer you stay alive
            energy -= 0.1 + (framesAlive * 0.0001);
            if (energy <= 0) die();

            // FEWER BUT DEADLIER ENEMIES
            // Hard cap of 3 enemies maximum. They spawn slightly faster as time goes on.
            if (viruses.size() < 3 && Math.random() < 0.02 + (framesAlive * 0.00005)) {
                viruses.add(new Virus());
            }

            if (nodes.isEmpty() || Math.random() < 0.005) spawnNode();

            // Update Lists
            updateEntities(nodes);
            updateEntities(viruses);
            updateEntities(particles);
            updateEntities(floaters);

            // Collisions & Graze
            checkCollisions();
        } else if (state == 2) {
            updateEntities(particles);
            updateEntities(floaters);
        }
    }

    private void checkCollisions() {
        // Data Nodes
        Iterator<DataNode> nit = nodes.iterator();
        while (nit.hasNext()) {
            DataNode n = nit.next();
            if (Math.hypot(n.x - playerX, n.y - playerY) < 25) {
                int pts = (int)(100 * multiplier);
                score += pts;
                energy = Math.min(100, energy + 25); // Gives more energy back to keep pace with drain
                multiplier += 0.5;
                spawnParticles(n.x, n.y, DATA_COLOR, 15);
                floaters.add(new FloatingText("+" + pts, n.x, n.y, DATA_COLOR));
                AudioEngine.playSound("ping");
                nit.remove();
            }
        }

        // Viruses
        for (Virus v : viruses) {
            double dist = Math.hypot(v.x - playerX, v.y - playerY);
            if (dist < 20) { // Hit
                energy -= 50; // MASSIVE DAMAGE (2 hits to kill)
                multiplier = 1.0;
                spawnParticles(playerX, playerY, DANGER_COLOR, 30);
                floaters.add(new FloatingText("CRITICAL HIT", playerX, playerY - 20, DANGER_COLOR));
                AudioEngine.playSound("kick");
                v.life = 0; // kill virus
            } else if (dist < 80 && !v.grazed) { // Increased graze radius because fewer enemies
                grazeCount++;
                multiplier += 0.5;
                energy = Math.min(100, energy + 5);
                v.grazed = true;
                floaters.add(new FloatingText("GRAZE", v.x, v.y, HUD_COLOR));
                AudioEngine.playSound("graze");
            }
        }
    }

    private void die() {
        state = 2;
        spawnParticles(playerX, playerY, HUD_COLOR, 150);
        AudioEngine.playSound("death");
    }

    private void spawnNode() {
        nodes.add(new DataNode(50 + Math.random() * (WIDTH - 100), 50 + Math.random() * (HEIGHT - 100)));
    }

    private void updateEntities(ArrayList<? extends Entity> list) {
        Iterator<? extends Entity> it = list.iterator();
        while (it.hasNext()) {
            if (!it.next().update()) it.remove();
        }
    }

    // --- RENDERING PIPELINE ---
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Grid Background
        g2.setColor(new Color(20, 25, 30, 100));
        for (int i = 0; i < WIDTH; i += 40) g2.drawLine(i, 0, i, HEIGHT);
        for (int i = 0; i < HEIGHT; i += 40) g2.drawLine(0, i, WIDTH, i);

        if (state == 0) drawBootScreen(g2);
        if (state == 1 || state == 2) {
            for (DataNode n : nodes) n.draw(g2);
            for (Virus v : viruses) v.draw(g2);
            for (Particle p : particles) p.draw(g2);

            if (state == 1) drawPlayer(g2);

            for (FloatingText ft : floaters) ft.draw(g2);
            drawHUD(g2);
        }

        if (state == 2) drawGameOver(g2);
    }

    private void drawPlayer(Graphics2D g2) {
        g2.setColor(HUD_COLOR);
        g2.setStroke(new BasicStroke(2));
        g2.drawOval((int)playerX - 12, (int)playerY - 12, 24, 24);
        g2.fillOval((int)playerX - 4, (int)playerY - 4, 8, 8);

        // Rotating radar ring speeds up as energy drops
        double rotationSpeed = 0.05 + ((100 - energy) * 0.002);
        g2.rotate(framesAlive * rotationSpeed, playerX, playerY);
        g2.drawArc((int)playerX - 20, (int)playerY - 20, 40, 40, 0, 90);
        g2.drawArc((int)playerX - 20, (int)playerY - 20, 40, 40, 180, 90);
        g2.rotate(-framesAlive * rotationSpeed, playerX, playerY);
    }

    private void drawHUD(Graphics2D g2) {
        g2.setFont(new Font("Monospaced", Font.BOLD, 14));

        // Top Left: Diagnostics
        drawHudPanel(g2, 10, 10, 220, 90);
        g2.setColor(HUD_COLOR);
        g2.drawString("SYS.OP.AURA // ONLINE", 20, 30);
        g2.drawString("UPTIME:  " + String.format("%.2f s", framesAlive / 60.0), 20, 50);
        g2.drawString("GRAZE:   " + grazeCount, 20, 70);
        g2.drawString(String.format("MULTI:   x%.1f", multiplier), 20, 90);

        // Top Right: Score
        drawHudPanel(g2, WIDTH - 250, 10, 240, 50);
        g2.setFont(new Font("Monospaced", Font.BOLD, 22));
        g2.drawString("SCORE " + String.format("%08d", score), WIDTH - 240, 42);

        // Bottom Center: Energy Bar
        int barW = 400;
        drawHudPanel(g2, WIDTH/2 - barW/2 - 10, HEIGHT - 50, barW + 20, 40);
        g2.setFont(new Font("Monospaced", Font.BOLD, 12));
        g2.drawString("CORE ENERGY", WIDTH/2 - barW/2, HEIGHT - 20);

        Color eColor = energy > 50 ? HUD_COLOR : energy > 25 ? DATA_COLOR : DANGER_COLOR;
        g2.setColor(eColor);
        g2.drawRect(WIDTH/2 - barW/2 + 85, HEIGHT - 32, barW - 85, 12);
        g2.fillRect(WIDTH/2 - barW/2 + 87, HEIGHT - 30, (int)((barW - 89) * (Math.max(0, energy)/100.0)), 9);
    }

    private void drawHudPanel(Graphics2D g2, int x, int y, int w, int h) {
        g2.setColor(new Color(0, 40, 30, 180));
        g2.fillRect(x, y, w, h);
        g2.setColor(HUD_COLOR);
        g2.setStroke(new BasicStroke(1));
        g2.drawRect(x, y, w, h);
        g2.fillRect(x, y, 5, 5);
        g2.fillRect(x+w-5, y, 5, 5);
        g2.fillRect(x, y+h-5, 5, 5);
        g2.fillRect(x+w-5, y+h-5, 5, 5);
    }

    private void drawBootScreen(Graphics2D g2) {
        g2.setFont(new Font("Monospaced", Font.BOLD, 24));
        g2.setColor(HUD_COLOR);
        int y = HEIGHT / 2 - 20;
        for (int i = 0; i <= bootLineIndex; i++) {
            if (i == bootLines.length) break;
            String text = (i == bootLineIndex) ? bootLines[i].substring(0, Math.min(bootCharIndex, bootLines[i].length())) : bootLines[i];
            g2.drawString(text, WIDTH / 2 - 150, y);
            y += 40;
        }
    }

    private void drawGameOver(Graphics2D g2) {
        g2.setColor(new Color(255, 0, 0, 40));
        g2.fillRect(0, 0, WIDTH, HEIGHT);
        drawHudPanel(g2, WIDTH/2 - 200, HEIGHT/2 - 100, 400, 200);
        g2.setColor(DANGER_COLOR);
        g2.setFont(new Font("Monospaced", Font.BOLD, 36));
        g2.drawString("SYSTEM OVERLOAD", WIDTH/2 - 155, HEIGHT/2 - 30);
        g2.setColor(HUD_COLOR);
        g2.setFont(new Font("Monospaced", Font.PLAIN, 18));
        g2.drawString("FINAL SCORE: " + score, WIDTH/2 - 90, HEIGHT/2 + 10);
        if (System.currentTimeMillis() % 1000 < 500)
            g2.drawString("> CLICK TO REBOOT", WIDTH/2 - 90, HEIGHT/2 + 50);
    }

    private void spawnParticles(double x, double y, Color c, int count) {
        for (int i=0; i<count; i++) particles.add(new Particle(x, y, c));
    }

    // --- ENTITY CLASSES ---
    abstract class Entity { abstract boolean update(); }

    class DataNode extends Entity {
        double x, y; int life = 0;
        DataNode(double x, double y) { this.x = x; this.y = y; }
        boolean update() { life++; return true; }
        void draw(Graphics2D g2) {
            g2.setColor(DATA_COLOR);
            g2.setStroke(new BasicStroke(2));
            int size = 12 + (int)(Math.sin(life * 0.1) * 3);
            g2.drawRect((int)x - size/2, (int)y - size/2, size, size);
            g2.rotate(life * 0.05, x, y);
            g2.drawRect((int)x - 4, (int)y - 4, 8, 8);
            g2.rotate(-life * 0.05, x, y);
        }
    }

    class Virus extends Entity {
        double x, y, vx, vy; boolean grazed = false; int life = 1;
        Virus() {
            double angle = Math.random() * Math.PI * 2;
            x = WIDTH/2.0 + Math.cos(angle) * (WIDTH);
            y = HEIGHT/2.0 + Math.sin(angle) * (WIDTH);
        }
        boolean update() {
            double angle = Math.atan2(playerY - y, playerX - x);
            vx += Math.cos(angle) * 0.15; // Increased acceleration
            vy += Math.sin(angle) * 0.15;

            // Speed cap increases the longer the player is alive!
            double maxSpeed = 4.5 + (framesAlive * 0.002);

            double speed = Math.hypot(vx, vy);
            if (speed > maxSpeed) { vx = (vx/speed)*maxSpeed; vy = (vy/speed)*maxSpeed; }
            x += vx; y += vy;
            return life > 0;
        }
        void draw(Graphics2D g2) {
            g2.setColor(grazed ? HUD_COLOR : DANGER_COLOR);
            Path2D.Double tri = new Path2D.Double();
            double a = Math.atan2(vy, vx);
            tri.moveTo(x + Math.cos(a)*15, y + Math.sin(a)*15); // Made slightly larger
            tri.lineTo(x + Math.cos(a + 2.5)*12, y + Math.sin(a + 2.5)*12);
            tri.lineTo(x + Math.cos(a - 2.5)*12, y + Math.sin(a - 2.5)*12);
            tri.closePath();
            g2.fill(tri);

            // Intimidating glowing core
            g2.setColor(Color.WHITE);
            g2.fillOval((int)x-3, (int)y-3, 6, 6);

            if(!grazed) {
                g2.setColor(new Color(255, 0, 0, 40));
                g2.fillOval((int)x-40, (int)y-40, 80, 80); // Danger aura
            }
        }
    }

    class Particle extends Entity {
        double x, y, vx, vy; int life, maxLife; Color c;
        Particle(double x, double y, Color c) {
            this.x = x; this.y = y; this.c = c;
            double a = Math.random() * Math.PI * 2;
            double s = Math.random() * 8 + 2; // Faster explosion
            vx = Math.cos(a)*s; vy = Math.sin(a)*s;
            maxLife = (int)(Math.random() * 20) + 10; life = maxLife;
        }
        boolean update() { x += vx; y += vy; vx *= 0.9; vy *= 0.9; life--; return life > 0; }
        void draw(Graphics2D g2) {
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int)(255 * (life/(double)maxLife))));
            g2.fillRect((int)x, (int)y, 3, 3);
        }
    }

    class FloatingText extends Entity {
        String text; double x, y; int life = 60; Color c;
        FloatingText(String text, double x, double y, Color c) { this.text = text; this.x = x; this.y = y; this.c = c; }
        boolean update() { y -= 1; life--; return life > 0; }
        void draw(Graphics2D g2) {
            g2.setFont(new Font("Monospaced", Font.BOLD, 14));
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.min(255, life * 5)));
            g2.drawString(text, (int)x - 10, (int)y);
        }
    }

    // --- PROCEDURAL AUDIO ENGINE ---
    private static class AudioEngine {
        private static final int SAMPLE_RATE = 8000;
        static void init() {}

        static void playSound(String type) {
            new Thread(() -> {
                try {
                    byte[] buf = null;
                    if (type.equals("ping")) buf = generatePing();
                    else if (type.equals("kick")) buf = generateKick();
                    else if (type.equals("graze")) buf = generateGraze();
                    else if (type.equals("death")) buf = generateDeath();
                    else if (type.equals("boot")) buf = generateBoot();

                    if(buf != null) {
                        AudioFormat af = new AudioFormat(SAMPLE_RATE, 8, 1, true, false);
                        SourceDataLine sdl = AudioSystem.getSourceDataLine(af);
                        sdl.open(af);
                        sdl.start();
                        sdl.write(buf, 0, buf.length);
                        sdl.drain();
                        sdl.close();
                    }
                } catch (Exception e) {}
            }).start();
        }

        private static byte[] generatePing() {
            byte[] buf = new byte[SAMPLE_RATE / 4];
            for (int i = 0; i < buf.length; i++) {
                double env = 1.0 - (i / (double)buf.length);
                buf[i] = (byte) (Math.sin(i / (SAMPLE_RATE / 880.0) * 2 * Math.PI) * 100 * env);
            }
            return buf;
        }

        private static byte[] generateKick() {
            byte[] buf = new byte[SAMPLE_RATE / 2];
            for (int i = 0; i < buf.length; i++) {
                double env = Math.exp(-i / 1000.0);
                double freq = 150 * env;
                buf[i] = (byte) (Math.sin(i / (SAMPLE_RATE / freq) * 2 * Math.PI) * 127 * env);
            }
            return buf;
        }

        private static byte[] generateGraze() {
            byte[] buf = new byte[SAMPLE_RATE / 8];
            for (int i = 0; i < buf.length; i++) {
                double env = 1.0 - (i / (double)buf.length);
                buf[i] = (byte) ((Math.random() * 2 - 1) * 60 * env);
            }
            return buf;
        }

        private static byte[] generateDeath() {
            byte[] buf = new byte[SAMPLE_RATE];
            for (int i = 0; i < buf.length; i++) {
                double env = 1.0 - (i / (double)buf.length);
                double freq = 200 - (i / (double)buf.length * 150);
                double wave = Math.sin(i / (SAMPLE_RATE / freq) * 2 * Math.PI) > 0 ? 1 : -1;
                buf[i] = (byte) (wave * 100 * env);
            }
            return buf;
        }

        private static byte[] generateBoot() {
            byte[] buf = new byte[SAMPLE_RATE / 2];
            for (int i = 0; i < buf.length; i++) {
                double env = Math.min(1.0, i / 1000.0) * (1.0 - (i / (double)buf.length));
                buf[i] = (byte) (Math.sin(i / (SAMPLE_RATE / 660.0) * 2 * Math.PI) * 50 * env);
            }
            return buf;
        }
    }

    public static void main(String[] args) {
        JFrame frame = new JFrame("A.U.R.A.");
        frame.setUndecorated(true);
        frame.add(new Game());
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}