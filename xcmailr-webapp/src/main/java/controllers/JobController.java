/**  
 *  Copyright 2013 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. 
 *
 */
package controllers;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.MimeMessage;
import org.joda.time.DateTime;
import org.mortbay.log.Log;
import org.subethamail.smtp.server.SMTPServer;
import models.MBox;
import models.User;
import ninja.lifecycle.Dispose;
import ninja.lifecycle.Start;
import ninja.utils.NinjaProperties;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import conf.XCMailrConf;

/**
 * Handles the Jobs which will be executed on Start and Stop of the Application
 * 
 * @author Patrick Thum, Xceptance Software Technologies GmbH, Germany
 */
@Singleton
public class JobController
{
    private final ScheduledExecutorService expirationService = Executors.newSingleThreadScheduledExecutor();

    private final ScheduledExecutorService mailTransportService = Executors.newSingleThreadScheduledExecutor();

    public SMTPServer smtpServer;

    public Queue<MimeMessage> mailQueue = new LinkedList<MimeMessage>();

    @Inject
    NinjaProperties ninjaProp;

    @Inject
    MemCachedSessionHandler mcsh;

    @Inject
    MailrMessageHandlerFactory mailrFactory;

    @Inject
    XCMailrConf xcmConf;

    /**
     * Starts the Mailserver, creates the Admin-Account specified in application.conf and Threads to expire the
     * Mailaddresses and the Mails which have to be sent
     */
    @Start(order = 90)
    public void startActions()
    {
        String pwd = xcmConf.ADMIN_PASS;
        // interval to check for expired mailboxes
        int interval = xcmConf.MB_INT;

        // interval to check for new mails to send
        int mailinterval = xcmConf.MAIL_INT;
        // create the MemcachedHandler
        mcsh.create();
        Log.info("prod:" + ninjaProp.isProd() + " dev: " + ninjaProp.isDev() + " test: " + ninjaProp.isTest());

        if (!(pwd == null))
        { // if a pw is set in application.conf..
            String mail = xcmConf.ADMIN_ADD;
            if (!User.mailExists(mail))
            {// ...and the admin-acc doesn't exist
             // create the adminaccount
                User usr = new User("Site", "Admin", mail, pwd);
                // set the status and admin flags
                usr.setAdmin(true);
                usr.setActive(true);
                usr.save();
            }
        }
        // create the server for incoming mails
        smtpServer = new SMTPServer(mailrFactory);

        // using a dynamic port in test-mode
        int port;
        if (ninjaProp.isTest())
        {
            port = findAvailablePort(49152, 65535);
        }
        else
        {
            port = xcmConf.MB_PORT;
        }
        // set the port and start it
        smtpServer.setPort(port);
        smtpServer.start();

        // create the executor-service to check the mailboxes which were expired since the last run and disable them
        expirationService.scheduleAtFixedRate(new Runnable()
        {
            @Override
            public void run()
            {
                Log.info("mbox-scheduler run");
                int size = xcmConf.MB_SIZE;
                List<MBox> mbList = MBox.getNextBoxes(size);
                ListIterator<MBox> it = mbList.listIterator();
                DateTime dt = new DateTime();
                while (it.hasNext())
                {
                    MBox mb = it.next();
                    if (dt.isAfter(mb.getTs_Active()) && !(mb.getTs_Active() == 0))
                    { // this element is expired
                        mb.enable();
                    }
                }
            }
        }, new Long(1), new Long(interval), TimeUnit.MINUTES);

        mailTransportService.scheduleAtFixedRate(new Runnable()
        {
            @Override
            public void run() // Mailjob
            {
                Log.info("mailjob run " + mailQueue.size());
                MimeMessage message = mailQueue.poll();

                while (!(message == null))
                {
                    Log.info("Mailjob: Message found");
                    try
                    {
                        if (!ninjaProp.isTest()) // TODO maybe remove this (no messages will be sent when in test-mode)
                        {
                            Transport.send(message);
                        }
                        Log.info("Message sent");
                    }
                    catch (MessagingException e)
                    {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                    message = mailQueue.poll();
                }
            }
        }, new Long(1), new Long(mailinterval), TimeUnit.SECONDS);
    }

    /**
     * adds a Message to the Mailqueue
     * 
     * @param msg
     *            the Message to add
     */
    public void addMessage(MimeMessage msg)
    {
        mailQueue.add(msg);
    }

    /**
     * Stops the Threads and the SMTP-Server
     */
    @Dispose(order = 90)
    public void stopActions()
    {
        // stop the forwarding-service
        smtpServer.stop();

        // stop the job to expire the mailboxes
        expirationService.shutdown();
        mailTransportService.shutdown();
    }

    /**
     * finds an available Port in the Range of "min" and "max" Copyright information: this Method comes from
     * NinjaTestServer
     * 
     * @param min
     *            lower bound of ports to search
     * @param max
     *            upper bound of ports to search
     * @return an available port
     */
    // stolen from NinjaTestServer-source
    private static int findAvailablePort(int min, int max)
    {
        for (int port = min; port < max; port++)
        {
            try
            { // try to create a now port at "port"
              // if there's no exception, return it
                new ServerSocket(port).close();
                return port;
            }
            catch (IOException e)
            {
                // Must already be taken
            }
        }
        throw new IllegalStateException("Could not find available port in range " + min + " to " + max);
    }
}
