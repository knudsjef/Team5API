package app;

import io.jooby.*;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.io.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLOutput;
import java.util.*;

/**
 * server to act as an in-between for the website and the database
 */
public class App {
    // <certificate random string, [user id, date]
    private static HashMap<String, String[]> certificates = new HashMap<String, String[]>();

    public static void main(final String[] args) throws IOException, SQLException, ClassNotFoundException {
        // create server
        Jooby app = new Jooby();
        // locate ssl certificates generated with letsencrypt for the nimmo.us domain
        String certPath = "/etc/letsencrypt/live/nimmo.us/fullchain.pem"; // cert
        String keyPath = "/etc/letsencrypt/live/nimmo.us/privkey.pem"; // key
        // determine if ssl certificates are available in the environment
        if (new File(certPath).exists()) {
            // ssl certificates found, run only on secure port 443
            System.out.println("running in secure mode, 8080 and 8443");
            app.setServerOptions(new ServerOptions().setSecurePort(443).setMaxRequestSize(10485760 * 1000) // 10mB *
                    // 1000
                    .setSsl(SslOptions.x509(certPath, keyPath)));
        } else {
            // no ssl certificates found, run on port meant for local development
            System.out.println("running in local mode, 8080 only");
            app.setServerOptions(new ServerOptions().setMaxRequestSize(10485760 * 1000) // 10mB * 1000
                    .setPort(8090));
        }

        // allow all cors
        app.decorator(new CorsHandler());

        app.post("/getLeaderboard", (ctx) -> {
            Database.getConnection();
            String gameType = ctx.form("gameType").value();
            ctx.setResponseType(MediaType.json);
            ResultSet results = Database.query("Select u.real_name, s.score FROM users u, Scores s, Game_Results g WHERE u.user_id = s.user_id && s.game_id = g.game_id && g.game_name = 'BlackJack'");
            return Database.getJSONFromResultSet(results, "results");
        });

        Hashtable<String, GameContainer> gameContainers = new Hashtable<String, GameContainer>();

        app.post("/hostGame", ctx -> {
            String gameID = ctx.form("gameID").value();
            String gameType = ctx.form("gameType").value();
            gameContainers.put(gameID, new GameContainer(gameID, gameType));
            System.out.println(gameID);
            switch (gameType) {
                case "blackjack":
                    return "{\"blackjack\":\"Initialized\"}";
                case "solitaire":
                    return "{\"solitaire\":\"Initialized\"}";
                default:
                    return "{\"invalidGameTypeError\":\"" + gameType + "\"}";
            }
        });
        app.post("/blackjack", ctx -> {
            String gameID = ctx.form("gameID").value();
            String method = ctx.form("method").value();
            GameContainer gc = gameContainers.get(gameID);
            CardContainer deck = gc.cardContainers.get("deck");
            CardContainer discard = gc.cardContainers.get("discard");
            Boolean wasShuffled = false;
            String hand = "";
            Set<String> keyset;
            String[] keyArr;
            Card card;
            int numberPlayers = 0;
            switch (method) {
                case "setup":
                    int numPlayers = Integer.parseInt(ctx.form("numPlayers").value());
                    for (int i = 0; i < numPlayers; i++) {
                        gc.cardContainers.put("player" + (i + 1), new CardContainer(0));
                    }
                    gc.cardContainers.get("player1").isTurn = true;
                    gc.cardContainers.put("dealer", new CardContainer(0));
                    numberPlayers = numPlayers + 1;
                    return "{\"Shuffled\":\"false\"}";
                case "deal":
                    gc.roundActive = true;
                    Set<String> ks = gc.cardContainers.keySet();
                    for (String key : ks) {
                        if (!key.equals("deck") && !key.equals("discard")) {
                            CardContainer playerHand = gc.cardContainers.get(key);
                            if (!playerHand.cards.isEmpty()) {
                                for (Card cd : playerHand.cards) {
                                    discard.add(cd);
                                    playerHand.cards.remove(cd);
                                }
                            }
                            if (deck.cards.size() < 2) {
                                for (Card cd : discard.cards) {
                                    deck.add(cd);
                                    discard.remove(cd);
                                }
                                deck.shuffle();
                                wasShuffled = true;
                            }
                            playerHand.cards.add(deck.cards.get(0));
                            deck.cards.remove(0);
                            playerHand.cards.add(deck.cards.get(0));
                            playerHand.cards.get(1).isFaceUp = true;
                            deck.cards.remove(0);
                        }
                    }
                    return "{\"Shuffled\":\"" + wasShuffled + "\"}";
                case "hit":
                    hand = ctx.form("hand").value();
                    if (gc.cardContainers.get("deck").cards.isEmpty()) {
                        for (Card cd : discard.cards) {
                            deck.add(cd);
                            discard.remove(cd);
                        }
                        deck.shuffle();
                        wasShuffled = true;
                    }
                    card = deck.cards.get(0);
                    card.isFaceUp = true;
                    gc.cardContainers.get(hand).add(card);
                    deck.cards.remove(0);
                    return "{\"Shuffled\":\"" + wasShuffled + "\"}";
                case "stay":
                    hand = ctx.form("hand").value();
                    gc.cardContainers.get(hand).isTurn = false;
                    keyset = gc.cardContainers.keySet();
                    keyArr = keyset.toArray(new String[0]);
                    int findIndex = 2; //Starts at 2 to skip deck and discard hands
                    while (findIndex != keyArr.length) {
                        if (keyArr[findIndex].equals(hand)) {
                            findIndex++;
                            break;
                        }
                        findIndex++;
                    }
                    if (findIndex >= keyArr.length) {
                        findIndex -= keyArr.length; //Circle back around to the first hand
                        findIndex += 2; //skip deck and discard
                    }
                    gc.cardContainers.get(keyArr[findIndex]).isTurn = true;
                    break;
                case "getHand":
                    return gc.cardContainers.get(ctx.form("hand").value()).toJSON(false);
                case "checkIfTurn":
                    hand = ctx.form("hand").value();
                    if (gc.cardContainers.get(hand).isTurn) {
                        return "{\"isTurn\":\"true\"}";
                    }
                    if (hand.equals("player1") && gc.cardContainers.get("dealer").isTurn) {
                        CardContainer dealer = gc.cardContainers.get("dealer");
                        int score = 0, aceCount = 0;
                        //Put dealer logic here
                        dealer.cards.get(0).isFaceUp = true;
                        for (int i = 0; i < dealer.cards.size(); ++i) {
                            int val = dealer.getValue(i, "blackjack");
                            if (val == 1) {
                                aceCount++;
                                score += 11;
                            } else
                                score += val;
                        }
                        while (score <= 16) {
                            if (gc.cardContainers.get("deck").cards.isEmpty()) {
                                for (Card card1 : discard.cards) {
                                    deck.add(card1);
                                    discard.remove(card1);
                                }
                                deck.shuffle();
                                wasShuffled = true;
                            }
                            card = deck.cards.get(0);
                            card.isFaceUp = true;
                            dealer.add(card);
                            deck.cards.remove(0);
                            int val = dealer.getValue(dealer.cards.indexOf(card), "blackjack");
                            if (val == 1) {
                                aceCount++;
                                score += 11;
                            } else
                                score += val;

                            while (aceCount != 0) {
                                if (score > 21) {
                                    score -= 10;
                                    aceCount -= 1;
                                } else
                                    break;
                            }
                        }
                        gc.roundActive = false;
                        keyset = gc.cardContainers.keySet();
                        for (String key : keyset) {
                            if (!key.equals("deck") && !key.equals("discard")) {
                                CardContainer playerHand = gc.cardContainers.get(key);
                                for (Card cd : playerHand.cards) {
                                    cd.isFaceUp = true;
                                }
                            }
                        }
                    }
                    //Add End Round Logic
                    if (!gc.roundActive) {
                        int dealerScore = gc.cardContainers.get("dealer").addCards("blackjack");
                        int playerScore = gc.cardContainers.get(hand).addCards("blackjack");
                        if(playerScore>21){
                            return "{\"WinOrLose\":\"Lose\"}";
                        }
                        else if(dealerScore>21){
                            return "{\"WinOrLose\":\"Win\"}";
                        }
                        else if(dealerScore > playerScore){
                            return "{\"WinOrLose\":\"Lose\"}";
                        }
                        else{
                            return "{\"WinOrLose\":\"Tie\"}";
                        }
                    }
                    return "{\"isTurn\":\"false\"}";
                case "showCards":
                    keyset = gc.cardContainers.keySet();
                    keyArr = keyset.toArray(new String[0]);
                    String json = "{";
                    for (int i = 2; i < keyArr.length - 1; i++) {
                        json += "\"" + keyArr[i] + "\"" + ":" + (gc.cardContainers.get(keyArr[i]).toJSON(true)) + ",";
                    }
                    json += "\"" + keyArr[keyArr.length - 1] + "\"" + ":" + (gc.cardContainers.get(keyArr[keyArr.length - 1]).toJSON(true)) + "}";
                    return json;
                default:
                    break;
            }
            return "{\"Shuffled\":\"false\"}";
        });

        // start the server
        app.start();
    }
}


class Card {
    int card;
    boolean isFaceUp;

    Card(int cardNumber) {
        card = cardNumber;
        isFaceUp = false;
    }

    Map<String, Object> toMap(boolean pub) {
        Map<String, Object> data = new HashMap<String, Object>();
        if (pub && !isFaceUp) {
            data.put("cardNum", -1);
        } else {
            data.put("cardNum", card);
        }
        data.put("isFaceUp", isFaceUp);
        return data;
    }
}

class CardContainer {
    ArrayList<Card> cards;
    boolean isTurn;

    void shuffle() {
        Collections.shuffle(cards);
    }

    boolean add(Card card) {
        cards.add(card);
        return true;
    }

    boolean remove(Card card) {
        cards.remove(card);
        return true;
    }

    CardContainer(int numCards) {
        cards = new ArrayList<Card>();
        for (int i = 0; i < numCards; i++) {
            cards.add(new Card(i));
        }
        shuffle();
        isTurn = false;
    }

    int addCards(String gameType) {
        switch (gameType) {
            case "blackjack":
                int score = 0,aceCount=0;
                for (int i = 0; i < cards.size(); ++i) {
                    int val = getValue(i, gameType);
                    if (val == 1) {
                        aceCount++;
                        score += 11;
                    } else
                        score += val;
                }
                while (aceCount != 0) {
                    if (score > 21) {
                        score -= 10;
                        aceCount -= 1;
                    } else
                        break;
                }
                return score;
            default:
                return 0;
        }
    }

    int getValue(int cardNum, String gameType) {
        int cardValue = 0;
        switch (gameType) {
            case "blackjack":
                cardValue = (cards.get(cardNum).card % 13) + 1;
                if (cardValue > 10)
                    return 10;
                else
                    return cardValue;
            default:
                return 0;
        }
    }

    String toJSON(boolean pub) {
        Map<String, Object> data = new HashMap<String, Object>();
        for (int i = 0; i < cards.size(); i++) {
            data.put("" + i, cards.get(i).toMap(pub));
        }
        JSONObject json = new JSONObject();
        json.putAll(data);
        return json.toJSONString();
    }
}


class GameContainer {
    Hashtable<String, CardContainer> cardContainers;
    private String ID;
    private String gameType;
    public boolean roundActive;

    boolean addContainer(String name, int numCards) {
        cardContainers.put(name, new CardContainer(numCards));
        return true;
    }

    GameContainer(String gameID, String type) {
        ID = gameID;
        gameType = type;
        roundActive = true;
        cardContainers = new Hashtable<String, CardContainer>();
        cardContainers.put("deck", new CardContainer(52));
        cardContainers.get("deck").shuffle();
        cardContainers.put("discard", new CardContainer(0));
    }
}