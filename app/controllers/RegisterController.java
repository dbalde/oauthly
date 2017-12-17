package controllers;

import com.typesafe.config.Config;
import config.JwtUtils;
import config.MailgunService;
import config.RecaptchaProtected;
import dtos.ConstraintGroups;
import dtos.RegistrationDto;
import dtos.Utils;
import models.ProviderLink;
import models.User;
import play.data.Form;
import play.data.FormFactory;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import repositories.ProviderLinkRepository;
import repositories.UserRepository;
import scala.Tuple2;

import javax.inject.Inject;
import java.util.Locale;

public class RegisterController extends Controller {

    @Inject
    private UserRepository userRepository;
    @Inject
    private ProviderLinkRepository providerLinkRepository;
    @Inject
    private JwtUtils jwtUtils;
    @Inject
    private FormFactory formFactory;
    @Inject
    //TODO change this to use generic MailService
    private MailgunService mailService;
    @Inject
    private Config config;

    public Result step1(String next) {
        return ok(views.html.register1.render(1, null, null, null, formFactory.form(RegistrationDto.class), next));
    }

    public Result step2(String next, String linkId) {
        ProviderLink link = providerLinkRepository.findById(linkId);
        if(link != null && link.getUserId() == null){
            String remoteUserEmail = link.getRemoteUserEmail();
            if(remoteUserEmail != null && userRepository.findByEmail(remoteUserEmail) != null){
                flash("warning", "The email "+ remoteUserEmail +" is already registered with us and it is probably yours. To use "+link.getProviderKey()+" login feature with this account, please first login then use the connection on your profile.");
                return redirect(routes.LoginController.get(next));
            }
            int state = remoteUserEmail != null ? 3 : 2;
            return ok(views.html.register1.render(state, link.getProviderKey(), remoteUserEmail, linkId, formFactory.form(RegistrationDto.class), next));
        } else {
            flash("error", "Invalid authentication, please try again");
            return redirect(routes.RegisterController.step1(next));
        }
    }

    @RecaptchaProtected
    public Result post1(String next, String linkId) {
        int state;
        ProviderLink link = null;
        if(linkId == null){
            state = 1;
        } else {
            link = providerLinkRepository.findById(linkId);
            if(link != null && link.getUserId() == null) {
                // TODO validate the remote user email with EmailValidator. If fails, treat as state=2
                state = link.getRemoteUserEmail() != null ? 3 : 2;
            } else {
                flash("error", "Invalid authentication, please try again");
                return redirect(routes.RegisterController.step1(next));
            }
        }

        Class<?> validationGroupClass = null;
        switch (state) {
            case 1:
                validationGroupClass = ConstraintGroups.Register1.class;
                break;
            case 2:
                validationGroupClass = ConstraintGroups.Register2.class;
                break;
            case 3:
                validationGroupClass = ConstraintGroups.Register3.class;
                break;
            default:
                throw new IllegalStateException("Unknown state="+state);
        }
        Form<RegistrationDto> form = formFactory.form(RegistrationDto.class, validationGroupClass).bindFromRequest();
        if(form.hasErrors()){
            flash("warning", "Form has errors");
            return badRequest(views.html.register1.render(state, link != null ? link.getProviderKey() : null, link != null ? link.getRemoteUserEmail() : null, linkId, form, next));
        }
        RegistrationDto dto = form.get();
        // normalize username
        dto.setUsernameNormalized(Utils.normalizeUsername(dto.getUsername()));
        if (userRepository.findByUsernameNormalized(dto.getUsernameNormalized()) != null) {
            form = form.withError("username", "Username already exists");
        }
        if(state == 1 || state == 2){
            // normalize email
            dto.setEmail(dto.getEmail().toLowerCase(Locale.ENGLISH));
            if (userRepository.findByEmail(dto.getEmail()) != null) {
                form = form.withError("email", "Email is already registered");
            }
        }
        // advanced email validation with commons-validator
//        if(!EmailValidator.getInstance().isValid(dto.getEmail())){
//            form = form.withError("email", "Invalid.userForm.email");
//        }
        if(form.hasErrors()){
            flash("warning", "Form has errors");
            return badRequest(views.html.register1.render(state, link != null ? link.getProviderKey() : null, link != null ? link.getRemoteUserEmail() : null, linkId, form, next));
        }
        if(state == 1 || state == 2){
            User user1 = new User();
            user1.setEmail(dto.getEmail());
            user1.setUsername(dto.getUsername());
            if(state == 1)
                user1.encryptThenSetPassword(dto.getPassword());
            String confirmationCode = jwtUtils.prepareEmailConfirmationCode(user1, state == 2 ? linkId : null);
            String content = emails.html.confirm.render(
                    routes.RegisterController.step5(next, confirmationCode).absoluteURL(request()),
                    config.getString("brand.name")).toString();
            mailService.sendEmail(dto.getEmail(), "Confirm your account", content);
            flash("info", "Success, confirmation email sent to "+dto.getEmail()+".");
            return redirect(routes.RegisterController.await());
        } else if (state == 3){
            User user = new User();
            user.setId(Utils.newId());
            user.setEmail(link.getRemoteUserEmail());
            user.setUsername(dto.getUsername());
            user.setUsernameNormalized(dto.getUsernameNormalized());
            user.setCreationTime(System.currentTimeMillis());
            if(userRepository.count() == 0)
                user.setAdmin(true);
            userRepository.save(user);
            link.setUserId(user.getId());
            providerLinkRepository.save(link);

            String cookieValue = jwtUtils.prepareCookie(user);
            Http.Cookie ltat = Http.Cookie.builder("ltat", cookieValue).withPath("/").withHttpOnly(true).withMaxAge(jwtUtils.getExpireCookie()).build();
            flash("info", "Registration successful");
            if(next != null && next.matches("^/.*$"))
                return redirect(next).withCookies(ltat);
            else
                return redirect(routes.ProfileController.get()).withCookies(ltat);
        } else {
            throw new IllegalStateException("Unknown state="+state);
        }

    }

    public Result step5(String next, String code) {
        Tuple2<User, String> tuple2 = jwtUtils.validateEmailConfirmationCode(code);
        if(tuple2 == null){
            flash("warning", "Cannot create your account: Invalid or expired confirmation code, please start over");
            return redirect(routes.LoginController.get(next));
        }
        User dto = tuple2._1;
        User byEmail = userRepository.findByEmail(dto.getEmail());
        if(byEmail != null){
            flash("info", "This email address was already confirmed, please log in");
            return redirect(routes.LoginController.get(next));
        }
        dto.setUsernameNormalized(Utils.normalizeUsername(dto.getUsername()));
        if (userRepository.findByUsernameNormalized(dto.getUsernameNormalized()) != null) {
            // somebody has registered with this username before this one could click the link
            flash("warning", "Cannot create your account: the username you entered ("+dto.getUsername()+") is no longer available, please start over");
            redirect(routes.RegisterController.step1(next));
        }
        String linkId = tuple2._2;
        ProviderLink link = null;
        if(linkId != null){
            link = providerLinkRepository.findById(linkId);
            if(link == null || link.getUserId() != null){
                flash("warning", "Cannot link your account with this provider, please start over");
                redirect(routes.RegisterController.step1(next));
            }
        }

        User user = new User();
        user.setId(Utils.newId());
        user.setEmail(dto.getEmail());
        user.setUsername(dto.getUsername());
        user.setUsernameNormalized(dto.getUsernameNormalized());
        user.setCreationTime(System.currentTimeMillis());
        if(dto.getPassword() != null){
            user.setPassword(dto.getPassword()); //already encrypted
        }
        if(userRepository.count() == 0)
            user.setAdmin(true);
        userRepository.save(user);

        if(link != null){
            link.setUserId(user.getId());
            providerLinkRepository.save(link);
        }

        String cookieValue = jwtUtils.prepareCookie(user);
        Http.Cookie ltat = Http.Cookie.builder("ltat", cookieValue).withPath("/").withHttpOnly(true).withMaxAge(jwtUtils.getExpireCookie()).build();
        flash("info", "Registration successful");
        if(next != null && next.matches("^/.*$"))
            return redirect(next).withCookies(ltat);
        else
            return redirect(routes.ProfileController.get()).withCookies(ltat);

    }

    public Result await(){
        return ok(views.html.generic.render("Register", "Thank you for registering, we have sent a confirmation link to your email address. Please check your inbox and click on the provided link to finalize account creation."));
    }
}