package com.limelight.ui;

import com.limelight.R;

/**
 * The projects and people Starwire is built on, for Settings > About > Credits. The same list as
 * the README's Credits table, so change both together.
 */
public final class AboutCredits {
    public static final class Credit {
        public final int section;
        public final String project;
        public final String author;
        public final int usedFor;
        public final String url;

        Credit(int section, String project, String author, int usedFor, String url) {
            this.section = section;
            this.project = project;
            this.author = author;
            this.usedFor = usedFor;
            this.url = url;
        }
    }

    private static final int MOONLIGHT = R.string.credits_section_moonlight;
    private static final int MICROPHONE = R.string.credits_section_microphone;
    private static final int USB = R.string.credits_section_usb;
    private static final int VIDEO = R.string.credits_section_video;
    private static final int HOSTS = R.string.credits_section_hosts;
    private static final int TOOLS = R.string.credits_section_tools;

    public static final Credit[] ALL = {
            new Credit(MOONLIGHT, "Moonlight Android", "Cameron Gutman and contributors",
                    R.string.credit_moonlight, "https://github.com/moonlight-stream/moonlight-android"),
            new Credit(MOONLIGHT, "Moonlight X", "MoreOrLessSoftware",
                    R.string.credit_moonlight_x, "https://github.com/MoreOrLessSoftware/moonlight-android"),
            new Credit(MOONLIGHT, "Artemis", "Yukino Song (ClassicOldSong)",
                    R.string.credit_artemis, "https://github.com/ClassicOldSong/moonlight-android"),
            new Credit(MOONLIGHT, "Artemide", "derflacco",
                    R.string.credit_artemide, "https://github.com/derflacco/moonlight-android"),
            new Credit(MOONLIGHT, "Moonlight (Axixi2233 fork)", "Axixi2233",
                    R.string.credit_axixi, "https://github.com/Axixi2233/moonlight-android"),
            new Credit(MOONLIGHT, "Moonlight X Turnip", "ncpt (NoCrypt)",
                    R.string.credit_turnip, "https://github.com/NoCrypt/moonlight-x-turnip"),
            new Credit(MOONLIGHT, "Moonlight V+", "qiin2333",
                    R.string.credit_vplus, "https://github.com/qiin2333/moonlight-vplus"),
            new Credit(MOONLIGHT, "Moonlit Artemis", "Mohammad Forhad (4had45)",
                    R.string.credit_moonlit, "https://github.com/4had45/moonlit-artemis"),
            new Credit(MOONLIGHT, "Moonlight Fold", "sblocktechnologies",
                    R.string.credit_fold, "https://github.com/sblocktechnologies/moonlight-fold"),
            new Credit(MOONLIGHT, "Nightjar", "Ramazan Kara",
                    R.string.credit_nightjar, "https://github.com/RamazanKara/nightjar"),

            new Credit(MICROPHONE, "Apollo microphone", "logabell",
                    R.string.credit_apollo_microphone, "https://github.com/logabell/apollo-microphone"),
            new Credit(MICROPHONE, "VBAN and VB-Cable", "VB-Audio (Vincent Burel)",
                    R.string.credit_vban, "https://vb-audio.com/Cable/"),

            new Credit(USB, "usbipdcpp", "yunsmall",
                    R.string.credit_usbipdcpp, "https://github.com/yunsmall/usbipdcpp"),

            new Credit(VIDEO, "PyroWave", "Hans-Kristian Arntzen (Themaister)",
                    R.string.credit_pyrowave, "https://github.com/Themaister/pyrowave"),
            new Credit(VIDEO, "libadrenotools", "Billy Laws (bylaws)",
                    R.string.credit_adrenotools, "https://github.com/bylaws/libadrenotools"),

            new Credit(HOSTS, "Vibepollo", "Chase Payne (Nonary)",
                    R.string.credit_vibepollo, "https://github.com/Nonary/Vibepollo"),
            new Credit(HOSTS, "Apollo", "ClassicOldSong",
                    R.string.credit_apollo_sunshine, "https://github.com/ClassicOldSong/Apollo"),
            new Credit(HOSTS, "Sunshine", "LizardByte",
                    R.string.credit_apollo_sunshine, "https://github.com/LizardByte/Sunshine"),

            new Credit(TOOLS, "Claude Code", "Anthropic",
                    R.string.credit_claude_code, "https://claude.com/claude-code"),
            new Credit(TOOLS, "REA", "morluto",
                    R.string.credit_rea, "https://github.com/morluto/rea"),
    };

    private AboutCredits() {}

    /** The section headings, in order. */
    public static int[] sections() {
        return new int[] {MOONLIGHT, MICROPHONE, USB, VIDEO, HOSTS, TOOLS};
    }
}
