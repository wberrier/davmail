/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2010  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package davmail.smtp;

import davmail.AbstractDavMailTestCase;
import davmail.DavGateway;
import davmail.Settings;
import davmail.exchange.DoubleDotOutputStream;
import davmail.exchange.ExchangeSession;
import davmail.exchange.ExchangeSessionFactory;
import davmail.util.IOUtil;
import org.apache.commons.codec.binary.Base64;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.*;
import java.net.Socket;

/**
 * Test smtp send message.
 */
public class TestSmtp extends AbstractDavMailTestCase {
    static Socket clientSocket;
    static BufferedOutputStream socketOutputStream;
    static BufferedInputStream socketInputStream;

    enum State {
        CHAR, CR, CRLF
    }

    protected void write(String line) throws IOException {
        socketOutputStream.write(line.getBytes("ASCII"));
        socketOutputStream.flush();
    }

    protected void writeLine(String line) throws IOException {
        write(line);
        socketOutputStream.write(13);
        socketOutputStream.write(10);
        socketOutputStream.flush();
    }

    protected String readLine() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        State state = State.CHAR;
        while (!(state == State.CRLF)) {
            int character = socketInputStream.read();
            if (state == State.CHAR) {
                if (character == 13) {
                    state = State.CR;
                }
            } else {
                if (character == 10) {
                    state = State.CRLF;
                } else {
                    state = State.CHAR;
                }
            }
            if (state == State.CHAR) {
                baos.write(character);
            }
        }
        return new String(baos.toByteArray(), "ASCII");
    }

    @Override
    public void setUp() throws IOException {
        super.setUp();
        if (clientSocket == null) {
            // start gateway
            DavGateway.start();
            clientSocket = new Socket("localhost", Settings.getIntProperty("davmail.smtpPort"));
            socketOutputStream = new BufferedOutputStream(clientSocket.getOutputStream());
            socketInputStream = new BufferedInputStream(clientSocket.getInputStream());

            String banner = readLine();
            assertNotNull(banner);
            //String credentials = (char) 0 + Settings.getProperty("davmail.username") + (char) 0 + Settings.getProperty("davmail.password");
            String credentials = Settings.getProperty("davmail.username")  + (char) 0 + Settings.getProperty("davmail.username") + (char) 0 + Settings.getProperty("davmail.password");
            writeLine("AUTH PLAIN " + new String(new Base64().encode(credentials.getBytes())));
            assertEquals("235 OK Authenticated", readLine());
        }
        if (session == null) {
            session = ExchangeSessionFactory.getInstance(Settings.getProperty("davmail.username"), Settings.getProperty("davmail.password"));
        }
    }

    public void sendAndCheckMessage(MimeMessage mimeMessage) throws IOException, MessagingException, InterruptedException {
        sendAndCheckMessage(mimeMessage, null);
    }

    public void sendAndCheckMessage(MimeMessage mimeMessage, String bcc) throws IOException, MessagingException, InterruptedException {
        sendAndCheckMessage(mimeMessage, null, bcc);
    }

    public void sendAndCheckMessage(MimeMessage mimeMessage, String from, String bcc) throws IOException, MessagingException, InterruptedException {
        // generate message id
        mimeMessage.saveChanges();
        // mimeMessage.writeTo(System.out);

        // copy Message-id to references header
        mimeMessage.addHeader("references", mimeMessage.getHeader("message-id")[0]);
        if (from != null) {
            writeLine("MAIL FROM:" + from);
        } else {
            writeLine("MAIL FROM:" + session.getEmail());
        }
        readLine();
        if (bcc != null) {
            writeLine("RCPT TO:" + bcc);
            readLine();
        }
        writeLine("RCPT TO:" + Settings.getProperty("davmail.to"));
        readLine();
        writeLine("DATA");
        assertEquals("354 Start mail input; end with <CRLF>.<CRLF>", readLine());
        mimeMessage.writeTo(new DoubleDotOutputStream(socketOutputStream));
        writeLine("");
        writeLine(".");
        assertEquals("250 Queued mail for delivery", readLine());
        // wait for asynchronous message send
        ExchangeSession.MessageList messages = null;
        for (int i = 0; i < 5; i++) {
            messages = session.searchMessages("Sent", session.headerIsEqualTo("references", mimeMessage.getMessageID()));
            if (messages.size() > 0) {
                break;
            }
            Thread.sleep(1000);
        }
        assertEquals(1, messages.size());
        ExchangeSession.Message sentMessage = messages.get(0);
        sentMessage.getMimeMessage().writeTo(System.out);
        assertEquals(mimeMessage.getDataHandler().getContent(), sentMessage.getMimeMessage().getDataHandler().getContent());
    }

    public void testSendSimpleMessage() throws IOException, MessagingException, InterruptedException {
        String body = "Test message";
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        mimeMessage.addHeader("From", session.getEmail());
        mimeMessage.addHeader("To", Settings.getProperty("davmail.to"));
        mimeMessage.setSubject("Test subject");
        mimeMessage.setText(body);
        sendAndCheckMessage(mimeMessage);
    }

    public void testInvalidFrom() throws IOException, MessagingException, InterruptedException {
        String body = "Test message";
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        mimeMessage.addHeader("From", "guessant@loca.net");
        mimeMessage.addHeader("To", Settings.getProperty("davmail.to"));
        mimeMessage.setSubject("Test subject");
        mimeMessage.setText(body);
        sendAndCheckMessage(mimeMessage, "guessant@loca.net", null);
    }

    public void testSendMessage() throws IOException, MessagingException, InterruptedException {
        String body = "Test message\r\n" +
                "Special characters: éèçà\r\n" +
                "Chinese: " + ((char) 0x604F) + ((char) 0x7D59);
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        mimeMessage.addHeader("To", Settings.getProperty("davmail.to"));
        mimeMessage.setSubject("Test subject");
        mimeMessage.setText(body, "UTF-8");
        sendAndCheckMessage(mimeMessage);
    }

    public void testBccMessage() throws IOException, MessagingException, InterruptedException {
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        mimeMessage.addHeader("to", Settings.getProperty("davmail.to"));
        mimeMessage.setSubject("Test subject dav");
        mimeMessage.setText("Test message");
        sendAndCheckMessage(mimeMessage, Settings.getProperty("davmail.bcc"));
    }

    public void testDotMessage() throws IOException, MessagingException, InterruptedException {
        String body = "First line\r\n.\r\nSecond line";
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        mimeMessage.addHeader("to", Settings.getProperty("davmail.to"));
        mimeMessage.setSubject("Test subject");
        mimeMessage.setText(body);
        sendAndCheckMessage(mimeMessage);
    }

    public void testSendMessageTwice() throws IOException, MessagingException, InterruptedException {
        Settings.setProperty("davmail.smtpCheckDuplicates", "true");
        String body = "First line\r\n.\r\nSecond line";
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        mimeMessage.addHeader("to", Settings.getProperty("davmail.to"));
        mimeMessage.setSubject("Test subject");
        mimeMessage.setText(body);
        sendAndCheckMessage(mimeMessage);
        sendAndCheckMessage(mimeMessage);
    }

    public void testComplexToMessage() throws IOException, MessagingException, InterruptedException {
        String body = "Test message";
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        mimeMessage.addHeader("To", "nickname <" + Settings.getProperty("davmail.to") + '>');
        mimeMessage.setSubject("Test subject");
        mimeMessage.setText(body);
        sendAndCheckMessage(mimeMessage);
    }

    public void testSendPlainTextMessage() throws IOException, MessagingException, InterruptedException {
        String body = "Test plain text message";
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        mimeMessage.addHeader("To", Settings.getProperty("davmail.to"));
        mimeMessage.setSubject("Test text/plain message");
        mimeMessage.setText(body);
        sendAndCheckMessage(mimeMessage);
    }

    public void testSendHtmlMessage() throws IOException, MessagingException, InterruptedException {
        String body = "Test html message <font color=\"#ff0000\">red</font>";
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        mimeMessage.addHeader("To", Settings.getProperty("davmail.to"));
        mimeMessage.setSubject("Test html message");
        mimeMessage.setContent(body, "text/html");
        sendAndCheckMessage(mimeMessage);
    }

    public void testQuit() throws IOException {
        writeLine("QUIT");
        assertEquals("221 Closing connection", readLine());
    }


    public void testBrokenMessage() throws MessagingException, IOException, InterruptedException {
        MimeMessage mimeMessage = new MimeMessage(null, new FileInputStream("test.eml"));
        sendAndCheckMessage(mimeMessage);
    }

}
