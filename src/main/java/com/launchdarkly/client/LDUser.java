package com.launchdarkly.client;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A {@code LDUser} object contains specific attributes of a user browsing your site. The only mandatory property property is the {@code key},
 * which must uniquely identify each user. For authenticated users, this may be a username or e-mail address. For anonymous users,
 * this could be an IP address or session ID.
 * <p>
 * Besides the mandatory {@code key}, {@code LDUser} supports two kinds of optional attributes: interpreted attributes (e.g. {@code ip} and {@code country})
 * and custom attributes.  LaunchDarkly can parse interpreted attributes and attach meaning to them. For example, from an {@code ip} address, LaunchDarkly can
 * do a geo IP lookup and determine the user's country.
 * <p>
 * Custom attributes are not parsed by LaunchDarkly. They can be used in custom rules-- for example, a custom attribute such as "customer_ranking" can be used to
 * launch a feature to the top 10% of users on a site.
 * <p>
 * If you want to pass an LDUser object to the front end to be used with the JavaScript SDK, simply call {@code Gson.toJson()} or
 * {@code Gson.toJsonTree()} on it.
 */
public class LDUser {
  private static final Logger logger = LoggerFactory.getLogger(LDUser.class);

  // Note that these fields are all stored internally as JsonPrimitive rather than String so that
  // we don't waste time repeatedly converting them to JsonPrimitive in the rule evaluation logic.
  private final JsonPrimitive key;
  private JsonPrimitive secondary;
  private JsonPrimitive ip;
  private JsonPrimitive email;
  private JsonPrimitive name;
  private JsonPrimitive avatar;
  private JsonPrimitive firstName;
  private JsonPrimitive lastName;
  private JsonPrimitive anonymous;
  private JsonPrimitive country;
  private Map<String, JsonElement> custom;
  Set<String> privateAttributeNames;

  protected LDUser(Builder builder) {
    if (builder.key == null || builder.key.equals("")) {
      logger.warn("User was created with null/empty key");
    }
    this.key = builder.key == null ? null : new JsonPrimitive(builder.key);
    this.ip = builder.ip == null ? null : new JsonPrimitive(builder.ip);
    this.country = builder.country == null ? null : new JsonPrimitive(builder.country.getAlpha2());
    this.secondary = builder.secondary == null ? null : new JsonPrimitive(builder.secondary);
    this.firstName = builder.firstName == null ? null : new JsonPrimitive(builder.firstName);
    this.lastName = builder.lastName == null ? null : new JsonPrimitive(builder.lastName);
    this.email = builder.email == null ? null : new JsonPrimitive(builder.email);
    this.name = builder.name == null ? null : new JsonPrimitive(builder.name);
    this.avatar = builder.avatar == null ? null : new JsonPrimitive(builder.avatar);
    this.anonymous = builder.anonymous == null ? null : new JsonPrimitive(builder.anonymous);
    this.custom = builder.custom == null ? null : ImmutableMap.copyOf(builder.custom);
    this.privateAttributeNames = builder.privateAttrNames == null ? null : ImmutableSet.copyOf(builder.privateAttrNames);
  }

  /**
   * Create a user with the given key
   *
   * @param key a {@code String} that uniquely identifies a user
   */
  public LDUser(String key) {
    this.key = new JsonPrimitive(key);
    this.custom = null;
    this.privateAttributeNames = null;
  }

  protected JsonElement getValueForEvaluation(String attribute) {
    // Don't use Enum.valueOf because we don't want to trigger unnecessary exceptions
    for (UserAttribute builtIn: UserAttribute.values()) {
      if (builtIn.name().equals(attribute)) {
        return builtIn.get(this);
      }
    }
    return getCustom(attribute);
  }

  JsonPrimitive getKey() {
    return key;
  }

  String getKeyAsString() {
    if (key == null) {
      return "";
    } else {
      return key.getAsString();
    }
  }

  JsonPrimitive getIp() {
    return ip;
  }

  JsonPrimitive getCountry() {
    return country;
  }

  JsonPrimitive getSecondary() {
    return secondary;
  }

  JsonPrimitive getName() {
    return name;
  }

  JsonPrimitive getFirstName() {
    return firstName;
  }

  JsonPrimitive getLastName() {
    return lastName;
  }

  JsonPrimitive getEmail() {
    return email;
  }

  JsonPrimitive getAvatar() {
    return avatar;
  }

  JsonPrimitive getAnonymous() {
    return anonymous;
  }

  JsonElement getCustom(String key) {
    if (custom != null) {
      return custom.get(key);
    }
    return null;
  }
  
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    LDUser ldUser = (LDUser) o;

    return Objects.equals(key,  ldUser.key) &&
        Objects.equals(secondary, ldUser.secondary) &&
        Objects.equals(ip, ldUser.ip) &&
        Objects.equals(email, ldUser.email) &&
        Objects.equals(name, ldUser.name) &&
        Objects.equals(avatar, ldUser.avatar) &&
        Objects.equals(firstName, ldUser.firstName) &&
        Objects.equals(lastName, ldUser.lastName) &&
        Objects.equals(anonymous, ldUser.anonymous) &&
        Objects.equals(country, ldUser.country) &&
        Objects.equals(custom, ldUser.custom) &&
        Objects.equals(privateAttributeNames, ldUser.privateAttributeNames);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, secondary, ip, email, name, avatar, firstName, lastName, anonymous, country, custom, privateAttributeNames);
  }

  // Used internally when including users in analytics events, to ensure that private attributes are stripped out.
  static class UserAdapterWithPrivateAttributeBehavior extends TypeAdapter<LDUser> {
    private static final Gson gson = new Gson();
    private final LDConfig config;

    public UserAdapterWithPrivateAttributeBehavior(LDConfig config) {
      this.config = config;
    }

    @Override
    public void write(JsonWriter out, LDUser user) throws IOException {
      if (user == null) {
        out.value((String)null);
        return;
      }
      
      // Collect the private attribute names
      Set<String> privateAttributeNames = new HashSet<String>(config.privateAttrNames);

      out.beginObject();
      // The key can never be private
      out.name("key").value(user.getKeyAsString());

      if (user.getSecondary() != null) {
        if (!checkAndAddPrivate("secondary", user, privateAttributeNames)) {
          out.name("secondary").value(user.getSecondary().getAsString());
        }
      }
      if (user.getIp() != null) {
        if (!checkAndAddPrivate("ip", user, privateAttributeNames)) {
          out.name("ip").value(user.getIp().getAsString());
        }
      }
      if (user.getEmail() != null) {
        if (!checkAndAddPrivate("email", user, privateAttributeNames)) {
          out.name("email").value(user.getEmail().getAsString());
        }
      }
      if (user.getName() != null) {
        if (!checkAndAddPrivate("name", user, privateAttributeNames)) {
          out.name("name").value(user.getName().getAsString());
        }
      }
      if (user.getAvatar() != null) {
        if (!checkAndAddPrivate("avatar", user, privateAttributeNames)) {
          out.name("avatar").value(user.getAvatar().getAsString());
        }
      }
      if (user.getFirstName() != null) {
        if (!checkAndAddPrivate("firstName", user, privateAttributeNames)) {
          out.name("firstName").value(user.getFirstName().getAsString());
        }
      }
      if (user.getLastName() != null) {
        if (!checkAndAddPrivate("lastName", user, privateAttributeNames)) {
          out.name("lastName").value(user.getLastName().getAsString());
        }
      }
      if (user.getAnonymous() != null) {
        out.name("anonymous").value(user.getAnonymous().getAsBoolean());
      }
      if (user.getCountry() != null) {
        if (!checkAndAddPrivate("country", user, privateAttributeNames)) {
          out.name("country").value(user.getCountry().getAsString());
        }
      }
      writeCustomAttrs(out, user, privateAttributeNames);
      writePrivateAttrNames(out, privateAttributeNames);

      out.endObject();
    }

    private void writePrivateAttrNames(JsonWriter out, Set<String> names) throws IOException {
      if (names.isEmpty()) {
        return;
      }
      out.name("privateAttrs");
      out.beginArray();
      for (String name : names) {
        out.value(name);
      }
      out.endArray();
    }

    private boolean checkAndAddPrivate(String key, LDUser user, Set<String> privateAttrs) {
      boolean result = config.allAttributesPrivate || config.privateAttrNames.contains(key) || (user.privateAttributeNames != null && user.privateAttributeNames.contains(key));
      if (result) {
        privateAttrs.add(key);
      }
      return result;
    }

    private void writeCustomAttrs(JsonWriter out, LDUser user, Set<String> privateAttributeNames) throws IOException {
      boolean beganObject = false;
      if (user.custom == null) {
        return;
      }
      for (Map.Entry<String, JsonElement> entry : user.custom.entrySet()) {
        if (!checkAndAddPrivate(entry.getKey(), user, privateAttributeNames)) {
          if (!beganObject) {
            out.name("custom");
            out.beginObject();
            beganObject = true;
          }
          out.name(entry.getKey());
          gson.toJson(entry.getValue(), JsonElement.class, out);
        }
      }
      if (beganObject) {
        out.endObject();
      }
    }

    @Override
    public LDUser read(JsonReader in) throws IOException {
      // We never need to unmarshal user objects, so there's no need to implement this
      return null;
    }
  }

  /**
   * A <a href="http://en.wikipedia.org/wiki/Builder_pattern">builder</a> that helps construct {@link LDUser} objects. Builder
   * calls can be chained, enabling the following pattern:
   * <pre>
   * LDUser user = new LDUser.Builder("key")
   *      .country("US")
   *      .ip("192.168.0.1")
   *      .build()
   * </pre>
   */
  public static class Builder {
    private String key;
    private String secondary;
    private String ip;
    private String firstName;
    private String lastName;
    private String email;
    private String name;
    private String avatar;
    private Boolean anonymous;
    private LDCountryCode country;
    private Map<String, JsonElement> custom;
    private Set<String> privateAttrNames;

    /**
     * Creates a builder with the specified key.
     *
     * @param key the unique key for this user
     */
    public Builder(String key) {
      this.key = key;
    }

    /**
    * Creates a builder based on an existing user.
    *
    * @param user an existing {@code LDUser}
    */
    public Builder(LDUser user) {
        JsonPrimitive userKey = user.getKey();
        if (userKey.isJsonNull()) {
            this.key = null;
        } else {
            this.key = user.getKeyAsString();
        }
        this.secondary = user.getSecondary() != null ? user.getSecondary().getAsString() : null;
        this.ip = user.getIp() != null ? user.getIp().getAsString() : null;
        this.firstName = user.getFirstName() != null ? user.getFirstName().getAsString() : null;
        this.lastName = user.getLastName() != null ? user.getLastName().getAsString() : null;
        this.email = user.getEmail() != null ? user.getEmail().getAsString() : null;
        this.name = user.getName() != null ? user.getName().getAsString() : null;
        this.avatar = user.getAvatar() != null ? user.getAvatar().getAsString() : null;
        this.anonymous = user.getAnonymous() != null ? user.getAnonymous().getAsBoolean() : null;
        this.country = user.getCountry() != null ? LDCountryCode.valueOf(user.getCountry().getAsString()) : null;
        this.custom = user.custom == null ? null : new HashMap<>(user.custom);
        this.privateAttrNames = user.privateAttributeNames == null ? null : new HashSet<>(user.privateAttributeNames);
    }
    
    /**
     * Sets the IP for a user.
     *
     * @param s the IP address for the user
     * @return the builder
     */
    public Builder ip(String s) {
      this.ip = s;
      return this;
    }

    /**
     * Sets the IP for a user, and ensures that the IP attribute is not sent back to LaunchDarkly.
     *
     * @param s the IP address for the user
     * @return the builder
     */
    public Builder privateIp(String s) {
      addPrivate("ip");
      return ip(s);
    }

    /**
     * Sets the secondary key for a user. This affects
     * <a href="https://docs.launchdarkly.com/docs/targeting-users#section-targeting-rules-based-on-user-attributes">feature flag targeting</a>
     * as follows: if you have chosen to bucket users by a specific attribute, the secondary key (if set)
     * is used to further distinguish between users who are otherwise identical according to that attribute.
     * @param s the secondary key for the user
     * @return the builder
     */
    public Builder secondary(String s) {
      this.secondary = s;
      return this;
    }

    /**
     * Sets the secondary key for a user, and ensures that the secondary key attribute is not sent back to
     * LaunchDarkly.
     * @param s the secondary key for the user
     * @return the builder
     */
    public Builder privateSecondary(String s) {
      addPrivate("secondary");
      return secondary(s);
    }

    /**
     * Set the country for a user.
     * <p>
     * The country should be a valid <a href="http://en.wikipedia.org/wiki/ISO_3166-1">ISO 3166-1</a>
     * alpha-2 or alpha-3 code. If it is not a valid ISO-3166-1 code, an attempt will be made to look up the country by its name.
     * If that fails, a warning will be logged, and the country will not be set.
     *
     * @param s the country for the user
     * @return the builder
     */
    public Builder country(String s) {
      country = LDCountryCode.getByCode(s, false);

      if (country == null) {
        List<LDCountryCode> codes = LDCountryCode.findByName("^" + Pattern.quote(s) + ".*");

        if (codes.isEmpty()) {
          logger.warn("Invalid country. Expected valid ISO-3166-1 code: " + s);
        } else if (codes.size() > 1) {
          // See if any of the codes is an exact match
          for (LDCountryCode c : codes) {
            if (c.getName().equals(s)) {
              country = c;
              return this;
            }
          }
          logger.warn("Ambiguous country. Provided code matches multiple countries: " + s);
          country = codes.get(0);
        } else {
          country = codes.get(0);
        }

      }
      return this;
    }

    /**
     * Set the country for a user, and ensures that the country attribute will not be sent back to LaunchDarkly.
     * <p>
     * The country should be a valid <a href="http://en.wikipedia.org/wiki/ISO_3166-1">ISO 3166-1</a>
     * alpha-2 or alpha-3 code. If it is not a valid ISO-3166-1 code, an attempt will be made to look up the country by its name.
     * If that fails, a warning will be logged, and the country will not be set.
     *
     * @param s the country for the user
     * @return the builder
     */
    public Builder privateCountry(String s) {
      addPrivate("country");
      return country(s);
    }

    /**
     * Set the country for a user.
     *
     * @param country the country for the user
     * @return the builder
     */
    public Builder country(LDCountryCode country) {
      this.country = country;
      return this;
    }

    /**
     * Set the country for a user, and ensures that the country attribute will not be sent back to LaunchDarkly.
     *
     * @param country the country for the user
     * @return the builder
     */
    public Builder privateCountry(LDCountryCode country) {
      addPrivate("country");
      return country(country);
    }

    /**
     * Sets the user's first name
     *
     * @param firstName the user's first name
     * @return the builder
     */
    public Builder firstName(String firstName) {
      this.firstName = firstName;
      return this;
    }


    /**
     * Sets the user's first name, and ensures that the first name attribute will not be sent back to LaunchDarkly.
     *
     * @param firstName the user's first name
     * @return the builder
     */
    public Builder privateFirstName(String firstName) {
      addPrivate("firstName");
      return firstName(firstName);
    }


    /**
     * Sets whether this user is anonymous.
     *
     * @param anonymous whether the user is anonymous
     * @return the builder
     */
    public Builder anonymous(boolean anonymous) {
      this.anonymous = anonymous;
      return this;
    }

    /**
     * Sets the user's last name.
     *
     * @param lastName the user's last name
     * @return the builder
     */
    public Builder lastName(String lastName) {
      this.lastName = lastName;
      return this;
    }

    /**
     * Sets the user's last name, and ensures that the last name attribute will not be sent back to LaunchDarkly.
     *
     * @param lastName the user's last name
     * @return the builder
     */
    public Builder privateLastName(String lastName) {
      addPrivate("lastName");
      return lastName(lastName);
    }


    /**
     * Sets the user's full name.
     *
     * @param name the user's full name
     * @return the builder
     */
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets the user's full name, and ensures that the name attribute will not be sent back to LaunchDarkly.
     *
     * @param name the user's full name
     * @return the builder
     */
    public Builder privateName(String name) {
      addPrivate("name");
      return name(name);
    }

    /**
     * Sets the user's avatar.
     *
     * @param avatar the user's avatar
     * @return the builder
     */
    public Builder avatar(String avatar) {
      this.avatar = avatar;
      return this;
    }

    /**
     * Sets the user's avatar, and ensures that the avatar attribute will not be sent back to LaunchDarkly.
     *
     * @param avatar the user's avatar
     * @return the builder
     */
    public Builder privateAvatar(String avatar) {
      addPrivate("avatar");
      return avatar(avatar);
    }


    /**
     * Sets the user's e-mail address.
     *
     * @param email the e-mail address
     * @return the builder
     */
    public Builder email(String email) {
      this.email = email;
      return this;
    }

    /**
     * Sets the user's e-mail address, and ensures that the e-mail address attribute will not be sent back to LaunchDarkly.
     *
     * @param email the e-mail address
     * @return the builder
     */
    public Builder privateEmail(String email) {
      addPrivate("email");
      return email(email);
    }

    /**
     * Adds a {@link java.lang.String}-valued custom attribute. When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">built-in
     * user attribute keys</a>, this custom attribute will be ignored.
     *
     * @param k the key for the custom attribute
     * @param v the value for the custom attribute
     * @return the builder
     */
    public Builder custom(String k, String v) {
      return custom(k, v == null ? null : new JsonPrimitive(v));
    }

    /**
     * Adds a {@link java.lang.Number}-valued custom attribute. When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">built-in
     * user attribute keys</a>, this custom attribute will be ignored.
     *
     * @param k the key for the custom attribute
     * @param n the value for the custom attribute
     * @return the builder
     */
    public Builder custom(String k, Number n) {
      return custom(k, n == null ? null : new JsonPrimitive(n));
    }

    /**
     * Add a {@link java.lang.Boolean}-valued custom attribute. When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">built-in
     * user attribute keys</a>, this custom attribute will be ignored.
     *
     * @param k the key for the custom attribute
     * @param b the value for the custom attribute
     * @return the builder
     */
    public Builder custom(String k, Boolean b) {
      return custom(k, b == null ? null : new JsonPrimitive(b));
    }

    /**
     * Add a custom attribute whose value can be any JSON type. When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">built-in
     * user attribute keys</a>, this custom attribute will be ignored.
     *
     * @param k the key for the custom attribute
     * @param v the value for the custom attribute
     * @return the builder
     */
    public Builder custom(String k, JsonElement v) {
      checkCustomAttribute(k);
      if (k != null && v != null) {
        if (custom == null) {
          custom = new HashMap<>();
        }
        custom.put(k, v);
      }
      return this;
    }

    /**
     * Add a list of {@link java.lang.String}-valued custom attributes. When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">built-in
     * user attribute keys</a>, this custom attribute will be ignored.
     *
     * @param k  the key for the list
     * @param vs the values for the attribute
     * @return the builder
     */
    public Builder customString(String k, List<String> vs) {
      JsonArray array = new JsonArray();
      for (String v : vs) {
        if (v != null) {
          array.add(new JsonPrimitive(v));
        }
      }
      return custom(k, array);
    }

    /**
     * Add a list of {@link java.lang.Number}-valued custom attributes. When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">built-in
     * user attribute keys</a>, this custom attribute will be ignored.
     *
     * @param k  the key for the list
     * @param vs the values for the attribute
     * @return the builder
     */
    public Builder customNumber(String k, List<Number> vs) {
      JsonArray array = new JsonArray();
      for (Number v : vs) {
        if (v != null) {
          array.add(new JsonPrimitive(v));
        }
      }
      return custom(k, array);
    }
    
    /**
     * Add a custom attribute with a list of arbitrary JSON values. When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">built-in
     * user attribute keys</a>, this custom attribute will be ignored.
     *
     * @param k  the key for the list
     * @param vs the values for the attribute
     * @return the builder
     */
    public Builder customValues(String k, List<JsonElement> vs) {
      JsonArray array = new JsonArray();
      for (JsonElement v : vs) {
        if (v != null) {
          array.add(v);
        }
      }
      return custom(k, array);
    }
    
    /**
     * Add a {@link java.lang.String}-valued custom attribute that will not be sent back to LaunchDarkly.
     * When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">built-in
     * user attribute keys</a>, this custom attribute will be ignored.
     *
     * @param k the key for the custom attribute
     * @param v the value for the custom attribute
     * @return the builder
     */
    public Builder privateCustom(String k, String v) {
      addPrivate(k);
      return custom(k, v);
    }

    /**
     * Add a {@link java.lang.Number}-valued custom attribute that will not be sent back to LaunchDarkly.
     * When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">built-in
     * user attribute keys</a>, this custom attribute will be ignored.
     *
     * @param k the key for the custom attribute
     * @param n the value for the custom attribute
     * @return the builder
     */
    public Builder privateCustom(String k, Number n) {
      addPrivate(k);
      return custom(k, n);
    }

    /**
     * Add a {@link java.lang.Boolean}-valued custom attribute that will not be sent back to LaunchDarkly.
     * When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">built-in
     * user attribute keys</a>, this custom attribute will be ignored.
     *
     * @param k the key for the custom attribute
     * @param b the value for the custom attribute
     * @return the builder
     */
    public Builder privateCustom(String k, Boolean b) {
      addPrivate(k);
      return custom(k, b);
    }

    /**
     * Add a custom attribute of any JSON type, that will not be sent back to LaunchDarkly.
     * When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">built-in
     * user attribute keys</a>, this custom attribute will be ignored.
     *
     * @param k the key for the custom attribute
     * @param v the value for the custom attribute
     * @return the builder
     */
    public Builder privateCustom(String k, JsonElement v) {
      addPrivate(k);
      return custom(k, v);
    }
    
    /**
     * Add a list of {@link java.lang.String}-valued custom attributes. When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
     *   built-in user attribute keys</a>, this custom attribute will be ignored. The custom attribute value will not be sent
     *   back to LaunchDarkly in analytics events.
     *
     * @param k  the key for the list. When set to one of the built-in user attribute keys, this custom attribute will be ignored.
     * @param vs the values for the attribute
     * @return the builder
     */
    public Builder privateCustomString(String k, List<String> vs) {
      addPrivate(k);
      return customString(k, vs);
    }

    /**
     * Add a list of {@link java.lang.Integer}-valued custom attributes. When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
     *   built-in user attribute keys</a>, this custom attribute will be ignored. The custom attribute value will not be sent
     *   back to LaunchDarkly in analytics events.
     *
     * @param k  the key for the list. When set to one of the built-in user attribute keys, this custom attribute will be ignored.
     * @param vs the values for the attribute
     * @return the builder
     */
    public Builder privateCustomNumber(String k, List<Number> vs) {
      addPrivate(k);
      return customNumber(k, vs);
    }

    /**
     * Add a custom attribute with a list of arbitrary JSON values. When set to one of the
     * <a href="http://docs.launchdarkly.com/docs/targeting-users#targeting-based-on-user-attributes">
     * built-in user attribute keys</a>, this custom attribute will be ignored. The custom attribute value will not be sent
     * back to LaunchDarkly in analytics events.
     *
     * @param k  the key for the list
     * @param vs the values for the attribute
     * @return the builder
     */
    public Builder privateCustomValues(String k, List<JsonElement> vs) {
      addPrivate(k);
      return customValues(k, vs);
    }
    
    private void checkCustomAttribute(String key) {
      for (UserAttribute a : UserAttribute.values()) {
        if (a.name().equals(key)) {
          logger.warn("Built-in attribute key: " + key + " added as custom attribute! This custom attribute will be ignored during Feature Flag evaluation");
          return;
        }
      }
    }

    private void addPrivate(String key) {
      if (privateAttrNames == null) {
        privateAttrNames = new HashSet<>();
      }
      privateAttrNames.add(key);
    }
    
    /**
     * Builds the configured {@link com.launchdarkly.client.LDUser} object.
     *
     * @return the {@link com.launchdarkly.client.LDUser} configured by this builder
     */
    public LDUser build() {
      return new LDUser(this);
    }
  }
}
