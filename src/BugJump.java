
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * Bug Jump - Enhanced Vertical Platformer
 *
 * Controls: Left / Right arrows (or A / D) - Move Space / Enter - Start or
 * restart | P - Pause / resume R - Restart after game over | Esc - Quit
 */
public class BugJump extends JPanel implements ActionListener {

    // ---------------------------------------------------------------- Constants
    static final int W = 400, H = 600;
    static final int FPS_DELAY = 16;                 // ~60 FPS

    static final double GRAVITY = 0.45;
    static final double BOUNCE_V = -12.5;            // Normal bounce
    static final double SPRING_V = -19.5;            // Spring bounce
    static final double MOVE_SPEED = 6.0;            // Slightly tuned horizontal speed
    static final int SCROLL_LINE = H / 2 - 40;       // Camera threshold

    static final int PLAT_W = 64, PLAT_H = 14;
    static final int PLAYER_PIXEL = 3;               // Sprite scale
    static final int PW = 10 * PLAYER_PIXEL, PH = 10 * PLAYER_PIXEL;

    static final int STAR_BONUS = 50;

    enum State {
        START, PLAYING, PAUSED, GAME_OVER
    }

    enum PType {
        STANDARD, MOVING, SPRING, CRUMBLING
    }

    // ---------------------------------------------------------------- Sprite
    static final String[] SPRITE = {
        "..#....#..",
        "...#..#...",
        "..######..",
        ".##.##.##.",
        ".########.",
        "..######..",
        ".#.####.#.",
        "#..#..#..#",
        "...#..#...",
        "..##..##.."
    };

    // ---------------------------------------------------------------- Entities
    static class Platform {

        double x, y;
        PType type;
        double dir = 1, speed = 1.2;
        boolean hasStar;
        boolean starTaken;
        boolean crumbling;
        int crumbleTimer;

        Platform(double x, double y, PType type) {
            this.x = x;
            this.y = y;
            this.type = type;
            if (type == PType.MOVING) {
                speed = 1.0 + Math.random() * 1.5;
                dir = Math.random() < 0.5 ? -1 : 1;
            }
        }

        double starX() {
            return x + PLAT_W / 2.0;
        }

        double starY() {
            return y - 24;
        }
    }

    // ---------------------------------------------------------------- Effects
    static class Particle {

        double x, y, vx, vy, life;
        Color color;

        Particle(double x, double y, double vx, double vy, Color color) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.color = color;
            this.life = 1.0;
        }
    }

    // ---------------------------------------------------------------- State
    private final Random rng = new Random();
    private final List<Platform> platforms = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final javax.swing.Timer timer = new javax.swing.Timer(FPS_DELAY, this);

    private State state = State.START;
    private double px, py, vy, vx;
    private double heightPx;
    private int bonus;
    private int score, hiScore;
    private boolean left, right;
    private int frame;
    private int bestCombo;
    private int currentCombo;
    private int lastStarFrame = -1000;

    private static final Path HI_FILE = Paths.get(System.getProperty("user.home"), ".bugjump_hi");

    // ---------------------------------------------------------------- Setup
    public BugJump() {
        setPreferredSize(new Dimension(W, H));
        setFocusable(true);
        setupKeyBindings();
        loadHiScore();
        resetGame();
        timer.start();
    }

    /**
     * Replaces KeyListener with KeyBindings for responsive controls.
     */
    private void setupKeyBindings() {
        InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();

        bindKey(im, am, "VK_LEFT", KeyEvent.VK_LEFT, () -> left = true, () -> left = false);
        bindKey(im, am, "VK_A", KeyEvent.VK_A, () -> left = true, () -> left = false);
        bindKey(im, am, "VK_RIGHT", KeyEvent.VK_RIGHT, () -> right = true, () -> right = false);
        bindKey(im, am, "VK_D", KeyEvent.VK_D, () -> right = true, () -> right = false);

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "ESC");
        am.put("ESC", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), "PAUSE");
        am.put("PAUSE", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (state == State.PLAYING) {
                    state = State.PAUSED;
                    left = right = false;
                } else if (state == State.PAUSED) {
                    state = State.PLAYING;
                }
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "SPACE");
        am.put("SPACE", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handleStartOrRestart();
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "RESTART");
        am.put("RESTART", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (state == State.GAME_OVER) {
                    resetGame();
                    state = State.PLAYING;
                }
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ENTER");
        am.put("ENTER", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handleStartOrRestart();
            }
        });
    }

    private void bindKey(InputMap im, ActionMap am, String name, int keyCode, Runnable pressAction, Runnable releaseAction) {
        im.put(KeyStroke.getKeyStroke(keyCode, 0, false), name + "_PRESS");
        am.put(name + "_PRESS", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pressAction.run();
            }
        });

        im.put(KeyStroke.getKeyStroke(keyCode, 0, true), name + "_RELEASE");
        am.put(name + "_RELEASE", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                releaseAction.run();
            }
        });
    }

    private void handleStartOrRestart() {
        if (state == State.START) {
            state = State.PLAYING;
        } else if (state == State.GAME_OVER) {
            resetGame();
            state = State.PLAYING;
        }
    }

    private void resetGame() {
        platforms.clear();
        particles.clear();
        heightPx = 0;
        bonus = 0;
        score = 0;
        vx = 0;
        currentCombo = 0;
        bestCombo = 0;
        lastStarFrame = -1000;
        left = right = false;

        Platform ground = new Platform(W / 2.0 - PLAT_W / 2.0, H - 60, PType.STANDARD);
        platforms.add(ground);
        px = W / 2.0 - PW / 2.0;
        py = ground.y - PH;
        vy = BOUNCE_V;

        generatePlatforms();
    }

    private void generatePlatforms() {
        double topY = H;
        for (Platform p : platforms) {
            topY = Math.min(topY, p.y);
        }

        while (topY > -60) {
            double maxGap = Math.min(130, 70 + heightPx / 450.0);
            double minGap = 45;
            topY -= minGap + rng.nextDouble() * (maxGap - minGap);

            double x = rng.nextDouble() * (W - PLAT_W);
            PType type = PType.STANDARD;
            double r = rng.nextDouble();
            double difficulty = Math.min(1.0, heightPx / 12000.0);
            double movingChance = 0.06 + difficulty * 0.20;
            double crumbleChance = 0.03 + difficulty * 0.14;

            if (r < 0.08) {
                type = PType.SPRING;
            } else if (r < 0.08 + movingChance) {
                type = PType.MOVING;
            } else if (r < 0.08 + movingChance + crumbleChance) {
                type = PType.CRUMBLING;
            }

            Platform p = new Platform(x, topY, type);
            if (type != PType.SPRING && rng.nextDouble() < 0.12) {
                p.hasStar = true;
            }
            platforms.add(p);
        }
    }

    // ---------------------------------------------------------------- Game Loop
    @Override
    public void actionPerformed(ActionEvent e) {
        frame++;
        if (state == State.PLAYING) {
            update();
        }
        updateParticles();
        repaint();
    }

    private void update() {
        vx = 0;
        if (left) {
            vx -= MOVE_SPEED;
        }
        if (right) {
            vx += MOVE_SPEED;
        }
        px += vx;

        // Screen wrapping
        if (px + PW < 0) {
            px = W;
        } else if (px > W) {
            px = -PW;
        }

        double prevBottom = py + PH;
        vy += GRAVITY;
        py += vy;

        // Move platforms and update crumbling platforms.
        for (Platform p : platforms) {
            if (p.type == PType.MOVING) {
                p.x += p.dir * p.speed;
                if (p.x < 0) {
                    p.x = 0;
                    p.dir = 1;
                }
                if (p.x > W - PLAT_W) {
                    p.x = W - PLAT_W;
                    p.dir = -1;
                }
            }
            if (p.crumbling && --p.crumbleTimer <= 0) {
                p.y = H + 100;
            }
        }
        platforms.removeIf(p -> p.y > H + 80);

        // Landing collision (supporting screen-wrapping edge cases)
        if (vy > 0) {
            double bottom = py + PH;
            for (Platform p : platforms) {
                if (p.crumbling) {
                    continue;
                }
                boolean crossed = prevBottom <= p.y + 4 && bottom >= p.y;

                // Normal position, right-wrap position, left-wrap position
                boolean overlapStandard = px + PW - 6 > p.x && px + 6 < p.x + PLAT_W;
                boolean overlapWrapRight = (px + W) + PW - 6 > p.x && (px + W) + 6 < p.x + PLAT_W;
                boolean overlapWrapLeft = (px - W) + PW - 6 > p.x && (px - W) + 6 < p.x + PLAT_W;

                if (crossed && (overlapStandard || overlapWrapRight || overlapWrapLeft)) {
                    py = p.y - PH;
                    vy = (p.type == PType.SPRING) ? SPRING_V : BOUNCE_V;
                    spawnParticles(px + PW / 2.0, p.y, 9,
                            p.type == PType.SPRING ? new Color(233, 196, 106) : new Color(38, 70, 83));
                    if (p.type == PType.CRUMBLING) {
                        p.crumbling = true;
                        p.crumbleTimer = 28;
                    }
                    break;
                }
            }
        }

        // A combo expires if the next star is not collected in time.
        if (frame - lastStarFrame >= 240) {
            currentCombo = 0;
        }

        // Collect stars
        for (Platform p : platforms) {
            if (p.hasStar && !p.starTaken) {
                double dx = (px + PW / 2.0) - p.starX();
                double dy = (py + PH / 2.0) - p.starY();
                if (dx * dx + dy * dy < 24 * 24) {
                    p.starTaken = true;
                    bonus += STAR_BONUS;
                    currentCombo = (frame - lastStarFrame < 240) ? currentCombo + 1 : 1;
                    lastStarFrame = frame;
                    bestCombo = Math.max(bestCombo, currentCombo);
                    spawnParticles(p.starX(), p.starY(), 18, new Color(255, 190, 65));
                }
            }
        }

        // Smooth camera scroll
        if (py < SCROLL_LINE) {
            double dy = SCROLL_LINE - py;
            py = SCROLL_LINE;
            heightPx += dy;
            for (Platform p : platforms) {
                p.y += dy;
            }
            platforms.removeIf(p -> p.y > H + 20);
            generatePlatforms();
        }

        score = (int) (heightPx / 10) + bonus + Math.max(0, currentCombo - 1) * 10;

        if (py > H) {
            state = State.GAME_OVER;
            if (score > hiScore) {
                hiScore = score;
                saveHiScore();
            }
        }
    }

    // ---------------------------------------------------------------- Rendering
    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;

        // High quality anti-aliased geometry with crisp pixel art scaling
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawEyeSoothingBackground(g);

        for (Platform p : platforms) {
            drawPlatform(g, p);
        }

        drawParticles(g);
        drawPlayer(g);
        drawHud(g);

        switch (state) {
            case START:
                drawStartScreen(g);
                break;
            case PAUSED:
                drawCenteredBox(g, "PAUSED", "Press P to resume");
                break;
            case GAME_OVER:
                drawGameOver(g);
                break;
            default:
                break;
        }
    }

    private void drawEyeSoothingBackground(Graphics2D g) {
        // Gradient background from soft morning sky blue to warm sunset lavender
        GradientPaint gradient = new GradientPaint(
                0, 0, Color.decode("#f3f3f2"),
                0, H, Color.decode("#9fc5ce")
        );
        g.setPaint(gradient);
        g.fillRect(0, 0, W, H);

        // Soft background visual grid
        g.setColor(new Color(255, 255, 255, 20));
        for (int i = 0; i < W; i += 40) {
            g.drawLine(i, 0, i, H);
        }
        for (int j = 0; j < H; j += 40) {
            g.drawLine(0, j, W, j);
        }
    }

    private void drawPlayer(Graphics2D g) {
        drawSprite(g, (int) px, (int) py);
        if (px < 0) {
            drawSprite(g, (int) px + W, (int) py);
        }
        if (px + PW > W) {
            drawSprite(g, (int) px - W, (int) py);
        }
    }

    private void drawSprite(Graphics2D g, int sx, int sy) {
        // Vibrant modern emerald green bug color with soft drop shadow
        g.setColor(new Color(243, 243, 242, 40));
        for (int row = 0; row < SPRITE.length; row++) {
            String line = SPRITE[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) == '#') {
                    g.fillRect(sx + col * PLAYER_PIXEL + 2, sy + row * PLAYER_PIXEL + 2, PLAYER_PIXEL, PLAYER_PIXEL);
                }
            }
        }

        g.setColor(new Color(18, 19, 20)); // Coral / Teal Bug accent
        for (int row = 0; row < SPRITE.length; row++) {
            String line = SPRITE[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) == '#') {
                    g.fillRect(sx + col * PLAYER_PIXEL, sy + row * PLAYER_PIXEL, PLAYER_PIXEL, PLAYER_PIXEL);
                }
            }
        }
    }

    private void drawPlatform(Graphics2D g, Platform p) {
        int x = (int) p.x, y = (int) p.y;

        // Platform drop shadow
        g.setColor(new Color(0, 0, 0, 25));
        g.fillRoundRect(x + 2, y + 3, PLAT_W, PLAT_H, 8, 8);

        switch (p.type) {
            case STANDARD:
                g.setColor(new Color(38, 70, 83, 95)); // Soothing slate blue
                g.fillRoundRect(x, y, PLAT_W, PLAT_H, 8, 8);
                g.setColor(new Color(255, 255, 255, 100));
                g.fillRoundRect(x + 2, y + 2, PLAT_W - 4, 3, 4, 4);
                break;
            case MOVING:
                g.setColor(new Color(0x26, 0x46, 0x53)); // Soft dark blue-grey
                g.fillRoundRect(x, y, PLAT_W, PLAT_H, 8, 8);
                g.setColor(new Color(0xE7, 0x6F, 0x51)); // Coral indicator accents
                for (int i = 6; i < PLAT_W - 6; i += 12) {
                    g.fillRect(x + i, y + 4, 6, PLAT_H - 8);
                }
                break;
            case SPRING:
                g.setColor(new Color(0xE9, 0xC4, 0x6A));
                g.fillRoundRect(x, y, PLAT_W, PLAT_H, 8, 8);
                int cx = x + PLAT_W / 2 - 10;
                Path2D zig = new Path2D.Double();
                zig.moveTo(cx, y);
                for (int i = 0; i < 5; i++) {
                    zig.lineTo(cx + (i % 2 == 0 ? 20 : 0), y - 3 - i * 3);
                }
                g.setColor(new Color(0x26, 0x46, 0x53));
                g.setStroke(new BasicStroke(2.5f));
                g.draw(zig);
                g.setStroke(new BasicStroke(1f));
                g.fillRect(cx - 2, y - 18, 24, 4);
                break;
            case CRUMBLING:
                g.setColor(p.crumbling ? new Color(150, 105, 83, 150) : new Color(170, 115, 83));
                g.fillRoundRect(x, y, PLAT_W, PLAT_H, 5, 5);
                g.setColor(new Color(92, 62, 49));
                g.drawLine(x + 16, y + 3, x + 24, y + 10);
                g.drawLine(x + 38, y + 2, x + 32, y + 9);
                if (p.crumbling) {
                    g.setColor(new Color(255, 255, 255, 130));
                    g.drawLine(x + 5, y + 4, x + 59, y + 4);
                }
                break;
        }

        if (p.hasStar && !p.starTaken) {
            drawStar(g, p.starX(), p.starY() + Math.sin(frame / 8.0) * 4);
        }
    }

    private void drawStar(Graphics2D g, double cx, double cy) {
        Path2D star = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double r = (i % 2 == 0) ? 11 : 5;
            double a = -Math.PI / 2 + i * Math.PI / 5;
            double sx = cx + Math.cos(a) * r, sy = cy + Math.sin(a) * r;
            if (i == 0) {
                star.moveTo(sx, sy);
            } else {
                star.lineTo(sx, sy);
            }
        }
        star.closePath();

        g.setColor(new Color(255, 200, 87));
        g.fill(star);
        g.setColor(new Color(230, 150, 0));
        g.draw(star);
    }

    private void drawHud(Graphics2D g) {
        g.setColor(new Color(255, 255, 255, 185));
        g.fillRoundRect(10, 10, 112, 48, 12, 12);
        g.fillRoundRect(W - 124, 10, 114, 48, 12, 12);
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.setColor(new Color(75, 91, 101));
        g.drawString("SCORE", 20, 25);
        g.drawString("PERSONAL BEST", W - 114, 25);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
        g.setColor(new Color(18, 35, 42));
        g.drawString(String.valueOf(score), 20, 47);
        String hi = String.valueOf(Math.max(hiScore, score));
        int w = g.getFontMetrics().stringWidth(hi);
        g.drawString(hi, W - 20 - w, 47);

        if (currentCombo > 1 && frame - lastStarFrame < 180) {
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.setColor(new Color(190, 112, 24));
            drawCentered(g, "STAR COMBO x" + currentCombo, 78);
        }
    }

    private void drawStartScreen(Graphics2D g) {
        drawCenteredBox(g, "BUG JUMP", "Press Space or Enter to Start");
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(new Color(0x26, 0x46, 0x53));
        drawCentered(g, "← → or A / D to Move", H / 2 + 40);
        drawCentered(g, "Collect stars, hit springs!", H / 2 + 80);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(0x4A, 0x55, 0x68));
        drawCentered(g, "Watch out for crumbling platforms", H / 2 + 105);
    }

    private void drawGameOver(Graphics2D g) {
        drawCenteredBox(g, "GAME OVER", "Score: " + score + "   Best: " + hiScore);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
        g.setColor(new Color(0xE7, 0x6F, 0x51));
        drawCentered(g, "Press R or Space to restart", H / 2 + 45);
    }

    private void drawCenteredBox(Graphics2D g, String title, String sub) {
        int bw = 320, bh = 130, bx = (W - bw) / 2, by = H / 2 - 70;

        // Soft frosted-glass style translucent card
        g.setColor(new Color(255, 255, 255, 220));
        g.fillRoundRect(bx, by, bw, bh, 20, 20);
        g.setColor(new Color(255, 255, 255, 120));
        g.drawRoundRect(bx, by, bw, bh, 20, 20);

        g.setColor(new Color(0x26, 0x46, 0x53));
        g.setFont(new Font("MONOSPACED", Font.BOLD, 30));
        drawCentered(g, title, by + 50);

        g.setFont(new Font("MONOSPACED", Font.PLAIN, 15));
        g.setColor(new Color(0x4A, 0x55, 0x68));
        drawCentered(g, sub, by + 85);
    }

    private void drawCentered(Graphics2D g, String s, int y) {
        int w = g.getFontMetrics().stringWidth(s);
        g.drawString(s, (W - w) / 2, y);
    }

    // ---------------------------------------------------------------- Particle Effects
    private void spawnParticles(double x, double y, int count, Color color) {
        for (int i = 0; i < count; i++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double speed = 0.8 + rng.nextDouble() * 2.8;
            particles.add(new Particle(x, y,
                    Math.cos(angle) * speed,
                    Math.sin(angle) * speed - 0.8,
                    color));
        }
    }

    private void updateParticles() {
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.x += p.vx;
            p.y += p.vy;
            p.vy += 0.08;
            p.life -= 0.035;
            if (p.life <= 0) {
                it.remove();
            }
        }
    }

    private void drawParticles(Graphics2D g) {
        for (Particle p : particles) {
            int alpha = (int) (255 * Math.max(0, p.life));
            g.setColor(new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(), alpha));
            int size = Math.max(2, (int) (5 * p.life));
            g.fillOval((int) p.x, (int) p.y, size, size);
        }
    }

    // ---------------------------------------------------------------- High Score Storage
    private void loadHiScore() {
        try {
            if (Files.exists(HI_FILE)) {
                hiScore = Math.max(0, Integer.parseInt(new String(Files.readAllBytes(HI_FILE)).trim()));
            }
        } catch (Exception ignored) {
            hiScore = 0;
        }
    }

    private void saveHiScore() {
        try {
            Files.write(HI_FILE, String.valueOf(hiScore).getBytes());
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- Main Entry Point
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Bug Jump");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setResizable(false);
            BugJump game = new BugJump();
            f.add(game);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
