module AuthenticatedSystem
  # Returns true or false if the user is logged in.
  # Preloads @current_user with the user model if they're logged in.
  def logged_in?
    if Java::OrgSonarServerPlatform::Platform.component(Java::OrgSonarServerUser::ThreadLocalUserSession.java_class).hasSession()
      !!current_user
    else
      false
    end
  end

  # Accesses the current user from the session.
  # Future calls avoid the database because nil is not equal to false.
  #
  # This method will generate a Java::OrgSonarServerExceptions::UnauthorizedException if user is unauthorized
  # (bad credentials, not authenticated by force authentication is set to true, etc...)
  #
  def current_user
    @current_user ||= login_from_java_user_session unless @current_user == false
  end

  # Store the given user
  def current_user=(new_user)
    if new_user
      @current_user = new_user
    else
      @current_user = false
    end
  end

  # Check if the user is authorized
  #
  # Override this method in your controllers if you want to restrict access
  # to only a few actions or if you want to check if the user
  # has the correct rights.
  #
  # Example:
  #
  #  # only allow nonbobs
  #  def authorized?
  #    current_user.login != "bob"
  #  end
  #
  def authorized?(action = action_name, resource = nil)
    logged_in?
  end

  # Filter method to enforce a login requirement.
  #
  # To require logins for all actions, use this in your controllers:
  #
  #   before_filter :login_required
  #
  # To require logins for specific actions, use this in your controllers:
  #
  #   before_filter :login_required, :only => [ :edit, :update ]
  #
  # To skip this in a subclassed controller:
  #
  #   skip_before_filter :login_required
  #
  def login_required
    authorized? || render_access_denied
  end

  # Redirect as appropriate when an access request fails.
  #
  # The default action is to redirect to the login screen.
  #
  # Override this method in your controllers if you want to have special
  # behavior in case the user is not authorized
  # to access the requested action.  For example, a popup window might
  # simply close itself.
  def render_access_denied
    respond_to do |format|
      format.html do
        store_location
        if logged_in?
          flash[:loginerror]='You are not authorized to access this page. Please log in with more privileges and try again.'
        end
        write_flash_to_cookie
        redirect_to url_for :controller => '/sessions', :action => 'new'
      end
      # format.any doesn't work in rails version < http://dev.rubyonrails.org/changeset/8987
      # Add any other API formats here.  (Some browsers, notably IE6, send Accept: */* and trigger
      # the 'format.any' block incorrectly. See http://bit.ly/ie6_borken or http://bit.ly/ie6_borken2
      # for a workaround.)
      format.any(:json, :xml) do
        request_http_basic_authentication 'Web Password'
      end
    end
  end

  # Store the URI of the current request in the session.
  #
  # We can return to this location by calling #redirect_back_or_default.
  def store_location
    flash[:return_to] = request.request_uri
  end

  # Redirect to the URI stored by the most recent store_location call or
  # to the passed default.  Set an appropriately modified
  #   after_filter :store_location, :only => [:index, :new, :show, :edit]
  # for any controller you want to be bounce-backable.
  def redirect_back_or_default(default)
    # Prevent CSRF attack -> do not accept absolute urls
    url = get_cookie_flash('return_to') || default
    begin
      url = URI(url).request_uri
    rescue
      url
    end
    anchor=params[:return_to_anchor]
    url += anchor if anchor && anchor.start_with?('#')
    redirect_to(url)
  end

  # Inclusion hook to make #current_user and #logged_in?
  # available as ActionView helper methods.
  def self.included(base)
    base.send :helper_method, :current_user, :logged_in?, :authorized? if base.respond_to? :helper_method
  end

  #
  # Login
  #

  # Called from #current_user.  First attempt to login by the user id stored in the session.
  #
  # This method will generate a Java::OrgSonarServerExceptions::UnauthorizedException if user is unauthorized
  # (bad credentials, not authenticated by force authentication is set to true, etc...)
  #
  def login_from_java_user_session
    userSession = Java::OrgSonarServerPlatform::Platform.component(Java::OrgSonarServerUser::UserSession.java_class)
    user_id = userSession.getUserId() if userSession && userSession.isLoggedIn()
    self.current_user = User.find_by_id(user_id) if user_id
  end

  #
  # Logout
  #

  # This is ususally what you want; resetting the session willy-nilly wreaks
  # havoc with forgery protection, and is only strictly necessary on login.
  # However, **all session state variables should be unset here**.
  def logout_keeping_session!
    # Kill server-side auth cookie
    @current_user.forget_me if @current_user.is_a? User
    @current_user = false     # not logged in, and don't do it for me
    session['user_id'] = nil   # keeps the session but kill our variable
    # explicitly kill any other session variables you set
  end

  # The session should only be reset at the tail end of a form POST --
  # otherwise the request forgery protection fails. It's only really necessary
  # when you cross quarantine (logged-out to logged-in).
  def logout_killing_session!
    logout_keeping_session!
    reset_session
  end

end
