package me.kodysimpson.cortexbot.bot;

import com.jagrosh.jdautilities.command.CommandClientBuilder;
import me.kodysimpson.cortexbot.bot.commands.*;
import me.kodysimpson.cortexbot.bot.listeners.MessageListener;
import me.kodysimpson.cortexbot.bot.listeners.OtherListener;
import me.kodysimpson.cortexbot.bot.listeners.ReactionListener;
import me.kodysimpson.cortexbot.model.Bounty;
import me.kodysimpson.cortexbot.model.Member;
import me.kodysimpson.cortexbot.repositories.BountyRepository;
import me.kodysimpson.cortexbot.repositories.MemberRepository;
import me.kodysimpson.cortexbot.repositories.UserRepository;
import me.kodysimpson.cortexbot.services.MemberService;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.security.auth.login.LoginException;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DiscordBotService {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private BountyRepository bountyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MemberService memberService;

    private JDA api;

    @Autowired
    DiscordConfiguration discordConfiguration;

    @PostConstruct
    public void init() {
        try {

            CommandClientBuilder commandClient = new CommandClientBuilder()
                    .setPrefix("$")

                    .setOwnerId("250856681724968960")
                    .setHelpWord("help")
                    .setActivity(Activity.listening("$help"))

                    //Add commands
                    .addCommand(new LeaderboardCommand(memberRepository))
                    .addCommand(new WebsiteCommand())
                    .addCommand(new BountyCommand())
                    .addCommand(new SuggestionCommand(discordConfiguration))
                    .addCommand(new CodeBlockCommand());

            api = JDABuilder.create(List.of(GatewayIntent.GUILD_EMOJIS, GatewayIntent.GUILD_MEMBERS,
                    GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS))
                    .setToken(discordConfiguration.getBotToken())
                    .disableCache(CacheFlag.ACTIVITY, CacheFlag.VOICE_STATE, CacheFlag.CLIENT_STATUS)
                    .addEventListeners(commandClient.build())
                    .addEventListeners(new MessageListener(memberRepository, bountyRepository, discordConfiguration))
                    .addEventListeners(new ReactionListener(bountyRepository, discordConfiguration))
                    .addEventListeners(new OtherListener(this, discordConfiguration))
                    .setAutoReconnect(true)
                    .setBulkDeleteSplittingEnabled(false)
                    .build();


        } catch (LoginException e) {
            e.printStackTrace();
        }
    }

    public JDA getApi() {
        return api;
    }

    public Guild getGuild() {
        return api.getGuildById(discordConfiguration.getGuildId());
    }

    public void postBounty(Bounty bounty) throws Exception{

        me.kodysimpson.cortexbot.model.User user = bounty.getOwner();

        //Create channel thread for the bounty
        getGuild().createTextChannel("bounty-" + bounty.getId())
                .setTopic("View on website: https://cortexdev.herokuapp.com/" + bounty.getId())
                .setParent(getGuild().getCategoryById(discordConfiguration.getBountyCategoryId()))
                .addRolePermissionOverride(Long.valueOf(discordConfiguration.getEveryoneRoleId()), null, Collections.singleton(Permission.VIEW_CHANNEL))
                .queue(textChannel -> {
                    bounty.setChannelID(textChannel.getIdLong());
                    bountyRepository.save(bounty);

                    MessageBuilder message = new MessageBuilder()
                            .append("Title: ", MessageBuilder.Formatting.BOLD).append(bounty.getTitle() + "\n")
                            .append("Description of Issue: ", MessageBuilder.Formatting.BOLD ).append(bounty.getDescription() + "\n")
                            .append("Language/Library: ", MessageBuilder.Formatting.BOLD).append(bounty.getTags() + "\n")
                            .append("Link to Code: ", MessageBuilder.Formatting.BOLD).append(bounty.getLinkToCode() + "\n")
                            .append("\nDiscuss below how to solve the issue. You can upvote ideas and solutions by reacting to a message with the green checkmark." +
                                    "Downvote with the red X emoji.");

                    textChannel.sendMessage(message.build()).queue();

                });

        TextChannel channel = api.getTextChannelById(discordConfiguration.getWantedChannelId());

        if (channel != null) {

            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(Color.blue)
                    .setAuthor("Posted by " + user.getFirstName() + " " + user.getLastName())
                    .setTitle("NEW BOUNTY: " + bounty.getTitle())
                    .setDescription(bounty.getDescription())
                    .addField("Link: ", "https://cortexdev.herokuapp.com/bounty/" + bounty.getId(), true)
                    .addField("", "Click the Checkmark below to join the discussion.", false);

            Emote emote = channel.getGuild().getEmoteById(discordConfiguration.getGreenTickId());

            channel.sendMessage(embed.build()).queue(message -> {
                bounty.setBountyMessageID(message.getIdLong());
                if (emote != null){
                    message.addReaction(emote).queue();
                }
                bountyRepository.save(bounty);
            });

        }else{
            throw new Exception("Unable to post Bounty");
        }

    }

    public void addRoleToMember(net.dv8tion.jda.api.entities.Member member, long roleId) {
        try {
            Role role = member.getGuild().getRoleById(roleId);

            if (role != null) {
                getGuild().addRoleToMember(member, role).queue();
            }
        } catch (IllegalArgumentException | InsufficientPermissionException | HierarchyException e) {
            System.out.println(member.getUser().getAsTag() + " did not get the role on join");
            System.out.println(e);
        }
    }

    @Scheduled(fixedRate = 10000)
    public void applyRegularRoles() {

        System.out.println("Updating top regulars");

        ArrayList<String> topTen = (ArrayList<String>) memberRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(Member::getPoints).reversed())
                .limit(10)
                .map(Member::getUserID)
                .collect(Collectors.toList());

        //Remove the regular role from the current members if they are not top ten
        getGuild().getMembers()
                .forEach(member -> {
                    if (member.getRoles().contains(getApi().getRoleById(discordConfiguration.getRegularRoleId())) && !topTen.contains(member.getId())) {
                        getGuild().removeRoleFromMember(member, getApi().getRoleById(discordConfiguration.getRegularRoleId())).queue();
                    }
                });

        //apply it to the top ten members
        topTen.stream()
                .map(id -> getGuild().getMemberById(id))
                .filter(Objects::nonNull)
                .forEach(member -> addRoleToMember(member, Long.valueOf(discordConfiguration.getRegularRoleId())));

    }

    public void postBountyMessage(Bounty bounty, me.kodysimpson.cortexbot.model.Message message) throws Exception{

        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(Color.BLUE);
        embed.setAuthor("Website");
        embed.setTitle(memberService.getUsername(message) + " has posted");
        embed.appendDescription(message.getMessage());

        getGuild().getTextChannelById(bounty.getChannelID()).sendMessage(embed.build()).queue();

    }


}
