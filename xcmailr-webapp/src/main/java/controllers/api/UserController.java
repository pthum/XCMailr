/**  
 *  Copyright 2017 the original author or authors.
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
package controllers.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;

import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import conf.XCMailrConf;
import controllers.CachingSessionHandler;
import controllers.MailrMessageSenderFactory;
import etc.HelperUtils;
import filters.SecureApiFilter;
import models.Domain;
import models.User;
import models.UserFormData;
import ninja.Context;
import ninja.FilterWith;
import ninja.Result;
import ninja.Results;
import ninja.i18n.Lang;
import ninja.i18n.Messages;
import ninja.params.PathParam;
import ninja.validation.JSR303Validation;
import ninja.validation.Validation;

/**
 * RESTful Controller for all user actions Handles the actions of the User-Object
 * 
 * @see User
 * @author Patrick Thum
 */
@Singleton
public class UserController
{
    @Inject
    CachingSessionHandler cachingSessionHandler;

    @Inject
    XCMailrConf xcmConfiguration;

    @Inject
    MailrMessageSenderFactory mailrSenderFactory;

    @Inject
    Messages msg;

    @Inject
    Lang lang;

    /**
     * creates the {@link User} <br/>
     * POST /api/user
     * 
     * @param context
     *            the Context of this Request
     * @param registerFormData
     *            the Data of the Registration-Form
     * @param validation
     *            Form validation
     * @return the Registration-Form and an error, or - if successful - the Index-Page
     */
    public Result createUser(Context context, @JSR303Validation UserFormData registerFormData, Validation validation)
    {
        final Optional<Result> optRes = Optional.of(Results.json());
        final List<String> errors = registerFormData.validateCreation(xcmConfiguration, validation, msg, context,
                                                                      optRes);

        if (errors.size() > 0)
        {
            return Results.json().render("errors", errors);
        }
        // create the user
        final User user = registerFormData.getAsUser();

        // generate the confirmation-token
        user.setConfirmation(HelperUtils.getRandomSecureString(20));
        user.setTs_confirm(DateTime.now().plusHours(xcmConfiguration.CONFIRMATION_PERIOD).getMillis());

        user.save();
        final Optional<String> language = Optional.of(user.getLanguage());
        mailrSenderFactory.sendConfirmAddressMail(user.getMail(), user.getForename(), String.valueOf(user.getId()),
                                                  user.getConfirmation(), language);
        // context.getFlashScope().success("registerUser_Flash_Successful");
        return Results.created(Optional.of(xcmConfiguration.APP_HOME + "/api/user/" + user.getId())).json();
    }

    /**
     * Returns all users
     * 
     * @param context
     *            the Context of this Request
     * @return a list of users as json
     */
    @FilterWith(SecureApiFilter.class)
    public Result getUsers(Context context)
    {
        final User reqUser = context.getAttribute("user", User.class);
        if (reqUser.isAdmin() == false)
        {
            return Results.forbidden().json().render("error", "Unauthorized");
        }
        final List<User> u = User.all();
        return Results.ok().json().render(u);
    }

    /**
     * Returns the specified user
     * 
     * @param context
     *            the Context of this Request
     * @param id
     *            the id of the user
     * @return the user as json
     */
    @FilterWith(SecureApiFilter.class)
    public Result getUser(Context context, @PathParam("id") final String id)
    {
        final long uid = Long.valueOf(id);
        final User reqUser = context.getAttribute("user", User.class);
        if (reqUser.isAdmin() == false && reqUser.getId() == uid)
        {
            return Results.forbidden().json().render("error", "Unauthorized");
        }
        final User u = User.getById(uid);
        if (u == null)
        {
            // FIXME
            return Results.notFound().json().render("errors", "User not found");
        }
        return Results.ok().json().render(u);
    }

    /**
     * Edits the {@link User}-Data <br/>
     * PUT /user/{id}
     * 
     * @param context
     *            the Context of this Request
     * @param userFormData
     *            the Data of the User-Edit-Form
     * @param validation
     *            Form validation
     * @return the Edit-Page again
     */

    @FilterWith(SecureApiFilter.class)
    public Result editUser(Context context, @PathParam("id") final String id,
                           @JSR303Validation UserFormData userFormData, Validation validation)
    {
        final Optional<Result> optRes = Optional.of(Results.json());
        final List<String> errors = new ArrayList<>();

        final User user = context.getAttribute("user", User.class);
        final String oldMail = user.getMail();
        if (validation.hasViolations())
        { // the filled form has errors
            errors.add(msg.get("flash_FormError", context, optRes).get());
        }

        // don't let the user register with one of our domains (prevent mail-loops)
        final String mailFromForm = userFormData.getMail();
        final String domainPart = mailFromForm.split("@")[1];
        if (Arrays.asList(xcmConfiguration.DOMAIN_LIST).contains(domainPart))
        {
            errors.add(msg.get("flash_NoLoop", context, optRes).get());
        }

        // block the editing, if the domain is not on the whitelist (and the whitelisting is active)
        if (xcmConfiguration.APP_WHITELIST)
        { // whitelisting is active
            if (Domain.getAll().isEmpty() == false && Domain.exists(domainPart) == false)
            { // the domain is not in the whitelist and the whitelist is not empty
                errors.add(msg.get("editUser_Flash_NotWhitelisted", context, optRes).get());
            }
        }

        if (user.checkPasswd(userFormData.getPassword()) == false)
        { // the authorization-process failed
            errors.add(msg.get("flash_FormError", context, optRes).get());
        }

        if (mailFromForm.equals(oldMail) == false)
        { // the user's mail-address changed
            if (User.mailExists(mailFromForm))
            { // return an error that the mail exists
                errors.add(msg.get("flash_MailExists", context, optRes).get());
            }
            else
            { // the address does not exist -> success!
                user.setMail(userFormData.getMail());
            }
        }
        // set the language
        if (Arrays.asList(xcmConfiguration.APP_LANGS).contains(userFormData.getLanguage()))
        { // set the selected language in the user-object and also in the application
          // user.setLanguage(userFormData.getLanguage());
          // lang.setLanguage(userFormData.getLanguage(), result);
        }

        // update the fore- and surname
        user.setForename(userFormData.getFirstName());
        user.setSurname(userFormData.getSurName());

        final String password1 = userFormData.getPasswordNew1();
        final String password2 = userFormData.getPasswordNew2();

        // check whether the new passwords are whitespace, null or empty strings
        if (StringUtils.isNotBlank(password1) && StringUtils.isNotBlank(password2))
        { // new password has been entered
            if (password1.equals(password2) == false)
            { // the passwords are not equal
                errors.add(msg.get("flash_PasswordsUnequal", context, optRes).get());
            }
            // the repetition is equal to the new pw
            if (password1.length() < xcmConfiguration.PW_LENGTH)
            { // the new password is too short
                errors.add(msg.get("flash_PasswordTooShort", context, optRes, xcmConfiguration.PW_LENGTH).get());
            }
            user.hashPasswd(password2);
        }
        if (errors.size() > 0)
        {
            return Results.json().render("errors", errors);
        }
        // update the user
        user.update();

        // update the entries in the caching-server
        if (oldMail.equals(mailFromForm) == false)
        { // update the cached session-list
            cachingSessionHandler.updateUsersSessionsOnChangedMail(oldMail, user.getMail());
            // set the new mail if it has changed correctly
            context.getSession().put("username", user.getMail());
        }
        // update all user objects for all sessions
        cachingSessionHandler.updateUsersSessions(user);

        // user-edit was successful
        return Results.json().render("success", msg.get("flash_DataChangeSuccess", context, optRes).get());
    }

    /**
     * Handles the {@link models.User User}-Delete-Function <br/>
     * DELETE /user/{id}
     * 
     * @param context
     *            the Context of this Request
     */
    @FilterWith(SecureApiFilter.class)
    public Result deleteUser(Context context, @PathParam("id") final String id)
    {
        final long uid = Long.valueOf(id);
        final Optional<Result> optRes = Optional.of(Results.json());
        final User reqUser = context.getAttribute("user", User.class);
        final User user = User.getById(uid);
        if (reqUser.isAdmin() == false && reqUser.getId() == uid)
        {
            return Results.forbidden().json().render("error", "Unauthorized");
        }
        if (user.isLastAdmin())
        { // can't delete the user, because he's the last admin
            return Results.forbidden().json().render("error",
                                                     msg.get("deleteUser_Flash_Failed", context, optRes).get());
        }
        // delete the session
        context.getSession().clear();
        cachingSessionHandler.deleteUsersSessions(user);
        // delete the user-account
        User.delete(user.getId());
        // context.getFlashScope().success("deleteUser_Flash_Success");
        return Results.noContent().json();
    }
}
