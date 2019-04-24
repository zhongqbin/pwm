/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2018 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.util.password;

import com.google.gson.reflect.TypeToken;
import com.novell.ldapchai.ChaiUser;
import com.novell.ldapchai.exception.ChaiError;
import com.novell.ldapchai.exception.ChaiPasswordPolicyException;
import com.novell.ldapchai.exception.ChaiUnavailableException;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import password.pwm.AppProperty;
import password.pwm.PwmApplication;
import password.pwm.PwmConstants;
import password.pwm.bean.SessionLabel;
import password.pwm.bean.pub.PublicUserInfoBean;
import password.pwm.config.Configuration;
import password.pwm.config.PwmSetting;
import password.pwm.config.option.ADPolicyComplexity;
import password.pwm.config.profile.PwmPasswordPolicy;
import password.pwm.config.profile.PwmPasswordRule;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmDataValidationException;
import password.pwm.error.PwmError;
import password.pwm.error.PwmOperationalException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.ldap.UserInfo;
import password.pwm.svc.PwmService;
import password.pwm.svc.stats.Statistic;
import password.pwm.util.PasswordCharCounter;
import password.pwm.util.PasswordData;
import password.pwm.util.java.JavaHelper;
import password.pwm.util.java.JsonUtil;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.macro.MacroMachine;
import password.pwm.util.operations.PasswordUtility;
import password.pwm.ws.client.rest.RestClientHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class PwmPasswordRuleValidator
{

    private static final PwmLogger LOGGER = PwmLogger.forClass( PwmPasswordRuleValidator.class );

    private static final boolean EXTRA_LOGGING = false;

    private final PwmApplication pwmApplication;
    private final PwmPasswordPolicy policy;
    private final Locale locale;
    private final Flag[] flags;


    public enum Flag
    {
        FailFast,
        BypassLdapRuleCheck,
    }

    public PwmPasswordRuleValidator(
            final PwmApplication pwmApplication,
            final PwmPasswordPolicy policy,
            final Flag... flags
    )
    {
        this.pwmApplication = pwmApplication;
        this.policy = policy;
        this.locale = PwmConstants.DEFAULT_LOCALE;
        this.flags = flags;
    }

    public PwmPasswordRuleValidator(
            final PwmApplication pwmApplication,
            final PwmPasswordPolicy policy,
            final Locale locale,
            final Flag... flags
    )
    {
        this.pwmApplication = pwmApplication;
        this.policy = policy;
        this.locale = locale;
        this.flags = flags;
    }

    public boolean testPassword(
            final PasswordData password,
            final PasswordData oldPassword,
            final UserInfo userInfo,
            final ChaiUser user
    )
            throws PwmDataValidationException, ChaiUnavailableException, PwmUnrecoverableException
    {
        final List<ErrorInformation> errorResults = validate( password, oldPassword, userInfo );

        if ( !errorResults.isEmpty() )
        {
            throw new PwmDataValidationException( errorResults.iterator().next() );
        }

        if ( user != null && !JavaHelper.enumArrayContainsValue( flags, Flag.BypassLdapRuleCheck ) )
        {
            try
            {
                LOGGER.trace( () -> "calling chai directory password validation checker" );
                user.testPasswordPolicy( password.getStringValue() );
            }
            catch ( UnsupportedOperationException e )
            {
                LOGGER.trace( () -> "Unsupported operation was thrown while validating password: " + e.toString() );
            }
            catch ( ChaiUnavailableException e )
            {
                pwmApplication.getStatisticsManager().incrementValue( Statistic.LDAP_UNAVAILABLE_COUNT );
                LOGGER.warn( "ChaiUnavailableException was thrown while validating password: " + e.toString() );
                throw e;
            }
            catch ( ChaiPasswordPolicyException e )
            {
                final ChaiError passwordError = e.getErrorCode();
                final PwmError pwmError = PwmError.forChaiError( passwordError );
                final ErrorInformation info = new ErrorInformation( pwmError == null ? PwmError.PASSWORD_UNKNOWN_VALIDATION : pwmError );
                LOGGER.trace( () -> "ChaiPasswordPolicyException was thrown while validating password: " + e.toString() );
                errorResults.add( info );
            }
        }

        if ( !errorResults.isEmpty() )
        {
            throw new PwmDataValidationException( errorResults.iterator().next() );
        }

        return true;
    }


    /**
     * Validates a password against the configured rules of PWM.  No directory operations
     * are performed here.
     *
     * @param password desired new password
     * @return true if the password is okay, never returns false.
     */
    private List<ErrorInformation> validate(
            final PasswordData password,
            final PasswordData oldPassword,
            final UserInfo userInfo
    )
            throws PwmUnrecoverableException
    {
        final List<ErrorInformation> internalResults = internalPwmPolicyValidator( password, oldPassword, userInfo );
        if ( pwmApplication != null )
        {
            final List<ErrorInformation> externalResults = invokeExternalRuleMethods(
                    pwmApplication.getConfig(),
                    policy,
                    password,
                    userInfo
            );
            internalResults.addAll( externalResults );
        }
        return internalResults;
    }

    public List<ErrorInformation> internalPwmPolicyValidator(
            final PasswordData password,
            final PasswordData oldPassword,
            final UserInfo userInfo
    )
            throws PwmUnrecoverableException
    {
        final String passwordString = password == null ? "" : password.getStringValue();
        final String oldPasswordString = oldPassword == null ? null : oldPassword.getStringValue();
        return internalPwmPolicyValidator( passwordString, oldPasswordString, userInfo );
    }

    @SuppressWarnings( "checkstyle:MethodLength" )
    public List<ErrorInformation> internalPwmPolicyValidator(
            final String passwordString,
            final String oldPasswordString,
            final UserInfo userInfo
    )
            throws PwmUnrecoverableException
    {
        final boolean failFast = JavaHelper.enumArrayContainsValue( flags, Flag.FailFast );

        // null check
        if ( passwordString == null )
        {
            return Collections.singletonList( new ErrorInformation(
                    PwmError.ERROR_INTERNAL,
                    "empty (null) new password" ) );
        }

        final List<ErrorInformation> errorList = new ArrayList<>();
        final PasswordRuleHelper ruleHelper = policy.getRuleHelper();
        final MacroMachine macroMachine = userInfo == null || userInfo.getUserIdentity() == null
                ? MacroMachine.forNonUserSpecific( pwmApplication, SessionLabel.SYSTEM_LABEL )
                : MacroMachine.forUser(
                pwmApplication,
                PwmConstants.DEFAULT_LOCALE,
                SessionLabel.SYSTEM_LABEL,
                userInfo.getUserIdentity()
        );

        //check against old password
        if ( oldPasswordString != null
                && oldPasswordString.length() > 0
                && ruleHelper.readBooleanValue( PwmPasswordRule.DisallowCurrent ) )
        {
            if ( oldPasswordString.length() > 0 )
            {
                if ( oldPasswordString.equalsIgnoreCase( passwordString ) )
                {
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_SAMEASOLD ) );
                }
            }

            //check chars from old password
            final int maxOldAllowed = ruleHelper.readIntValue( PwmPasswordRule.MaximumOldChars );
            if ( maxOldAllowed > 0 )
            {
                if ( oldPasswordString.length() > 0 )
                {
                    final String lPassword = passwordString.toLowerCase();
                    final Set<Character> dupeChars = new HashSet<>();

                    //add all dupes to the set.
                    for ( final char loopChar : oldPasswordString.toLowerCase().toCharArray() )
                    {
                        if ( lPassword.indexOf( loopChar ) != -1 )
                        {
                            dupeChars.add( loopChar );
                        }
                    }

                    //count the number of (unique) set elements.
                    if ( dupeChars.size() >= maxOldAllowed )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_OLD_CHARS ) );
                    }
                }
            }
        }

        if ( failFast && errorList.size() > 1 )
        {
            return errorList;
        }

        errorList.addAll( basicSyntaxRuleChecks( passwordString, policy, userInfo ) );

        if ( failFast && errorList.size() > 1 )
        {
            return errorList;
        }

        // check against disallowed values;
        if ( !ruleHelper.getDisallowedValues().isEmpty() )
        {
            final String lcasePwd = passwordString.toLowerCase();
            final Set<String> paramValues = new HashSet<>( ruleHelper.getDisallowedValues() );

            for ( final String loopValue : paramValues )
            {
                if ( loopValue != null && loopValue.length() > 0 )
                {
                    final String expandedValue = macroMachine.expandMacros( loopValue );
                    if ( StringUtils.isNotBlank( expandedValue ) )
                    {
                        final String loweredLoop = expandedValue.toLowerCase();
                        if ( lcasePwd.contains( loweredLoop ) )
                        {
                            errorList.add( new ErrorInformation( PwmError.PASSWORD_USING_DISALLOWED ) );
                        }
                    }
                }
            }
        }

        if ( failFast && errorList.size() > 1 )
        {
            return errorList;
        }

        // check disallowed attributes.
        if ( !policy.getRuleHelper().getDisallowedAttributes().isEmpty() )
        {
            final List<String> paramConfigs = policy.getRuleHelper().getDisallowedAttributes( PasswordRuleHelper.Flag.KeepThresholds );
            if ( userInfo != null )
            {
                final Map<String, String> userValues = userInfo.getCachedPasswordRuleAttributes();

                for ( final String paramConfig : paramConfigs )
                {
                    final String[] parts = paramConfig.split( ":" );

                    final String attrName = parts[ 0 ];
                    final String disallowedValue = StringUtils.defaultString( userValues.get( attrName ) );
                    final int threshold = parts.length > 1 ? NumberUtils.toInt( parts[ 1 ] ) : 0;

                    if ( PwmPasswordRuleUtil.containsDisallowedValue( passwordString, disallowedValue, threshold ) )
                    {
                        LOGGER.trace( () -> "password rejected, same as user attr " + attrName );
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_SAMEASATTR ) );
                    }
                }
            }
        }

        if ( failFast && errorList.size() > 1 )
        {
            return errorList;
        }

        {
            // check password strength
            final int requiredPasswordStrength = ruleHelper.readIntValue( PwmPasswordRule.MinimumStrength );
            if ( requiredPasswordStrength > 0 )
            {
                if ( pwmApplication != null )
                {
                    final int passwordStrength = PasswordUtility.judgePasswordStrength(
                            pwmApplication.getConfig(),
                            passwordString
                    );
                    if ( passwordStrength < requiredPasswordStrength )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_WEAK ) );
                        if ( EXTRA_LOGGING )
                        {
                            LOGGER.trace( () -> "password rejected, password strength of "
                                    + passwordStrength + " is lower than policy requirement of "
                                    + requiredPasswordStrength );
                        }
                    }
                }
            }
        }

        if ( failFast && errorList.size() > 1 )
        {
            return errorList;
        }

        // check regex matches.
        for ( final Pattern pattern : ruleHelper.getRegExMatch( macroMachine ) )
        {
            if ( !pattern.matcher( passwordString ).matches() )
            {
                errorList.add( new ErrorInformation( PwmError.PASSWORD_INVALID_CHAR ) );
                if ( EXTRA_LOGGING )
                {
                    LOGGER.trace( () -> "password rejected, does not match configured regex pattern: " + pattern.toString() );
                }
            }
        }

        if ( failFast && errorList.size() > 1 )
        {
            return errorList;
        }

        // check no-regex matches.
        for ( final Pattern pattern : ruleHelper.getRegExNoMatch( macroMachine ) )
        {
            if ( pattern.matcher( passwordString ).matches() )
            {
                errorList.add( new ErrorInformation( PwmError.PASSWORD_INVALID_CHAR ) );
                if ( EXTRA_LOGGING )
                {
                    LOGGER.trace( () -> "password rejected, matches configured no-regex pattern: " + pattern.toString() );
                }
            }
        }

        if ( failFast && errorList.size() > 1 )
        {
            return errorList;
        }

        // check char group matches
        if ( ruleHelper.readIntValue( PwmPasswordRule.CharGroupsMinMatch ) > 0 )
        {
            final List<Pattern> ruleGroups = ruleHelper.getCharGroupValues();
            if ( ruleGroups != null && !ruleGroups.isEmpty() )
            {
                final int requiredMatches = ruleHelper.readIntValue( PwmPasswordRule.CharGroupsMinMatch );
                int matches = 0;
                for ( final Pattern pattern : ruleGroups )
                {
                    if ( pattern.matcher( passwordString ).find() )
                    {
                        matches++;
                    }
                }
                if ( matches < requiredMatches )
                {
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_NOT_ENOUGH_GROUPS ) );
                }
            }
            if ( failFast && errorList.size() > 1 )
            {
                return errorList;
            }
        }

        if ( failFast && errorList.size() > 1 )
        {
            return errorList;
        }

        // check if the password is in the dictionary.
        if ( ruleHelper.readBooleanValue( PwmPasswordRule.EnableWordlist ) )
        {
            if ( pwmApplication != null )
            {
                if ( pwmApplication.getWordlistManager() != null && pwmApplication.getWordlistManager().status() == PwmService.STATUS.OPEN )
                {
                    final boolean found = pwmApplication.getWordlistManager().containsWord( passwordString );

                    if ( found )
                    {
                        //LOGGER.trace(pwmSession, "password rejected, in wordlist file");
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_INWORDLIST ) );
                    }
                }
                else
                {
                    /* noop */
                    //LOGGER.warn(pwmSession, "password wordlist checking enabled, but wordlist is not available, skipping wordlist check");
                }
            }
            if ( failFast && errorList.size() > 1 )
            {
                return errorList;
            }
        }

        if ( failFast && errorList.size() > 1 )
        {
            return errorList;
        }

        // check for shared (global) password history
        if ( pwmApplication != null )
        {
            if ( pwmApplication.getConfig().readSettingAsBoolean( PwmSetting.PASSWORD_SHAREDHISTORY_ENABLE )
                    && pwmApplication.getSharedHistoryManager().status() == PwmService.STATUS.OPEN )
            {
                final boolean found = pwmApplication.getSharedHistoryManager().containsWord( passwordString );

                if ( found )
                {
                    //LOGGER.trace(pwmSession, "password rejected, in global shared history");
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_INWORDLIST ) );
                }
            }
            if ( failFast && errorList.size() > 1 )
            {
                return errorList;
            }
        }

        return errorList;
    }

    private static final String REST_RESPONSE_KEY_ERROR = "error";
    private static final String REST_RESPONSE_KEY_ERROR_MSG = "errorMessage";

    public List<ErrorInformation> invokeExternalRuleMethods(
            final Configuration config,
            final PwmPasswordPolicy pwmPasswordPolicy,
            final PasswordData password,
            final UserInfo userInfo
    )
            throws PwmUnrecoverableException
    {
        final List<ErrorInformation> returnedErrors = new ArrayList<>();
        final String restURL = config.readSettingAsString( PwmSetting.EXTERNAL_PWCHECK_REST_URLS );
        final boolean haltOnError = Boolean.parseBoolean( config.readAppProperty( AppProperty.WS_REST_CLIENT_PWRULE_HALTONERROR ) );
        final Map<String, Object> sendData = new LinkedHashMap<>();


        if ( restURL == null || restURL.isEmpty() )
        {
            return Collections.emptyList();
        }

        {
            final String passwordStr = password == null ? "" : password.getStringValue();
            sendData.put( "password", passwordStr );
        }

        if ( pwmPasswordPolicy != null )
        {
            final LinkedHashMap<String, Object> policyData = new LinkedHashMap<>();
            for ( final PwmPasswordRule rule : PwmPasswordRule.values() )
            {
                policyData.put( rule.name(), pwmPasswordPolicy.getValue( rule ) );
            }
            sendData.put( "policy", policyData );
        }
        if ( userInfo != null )
        {
            final MacroMachine macroMachine = MacroMachine.forUser( pwmApplication, PwmConstants.DEFAULT_LOCALE, SessionLabel.SYSTEM_LABEL, userInfo.getUserIdentity() );
            final PublicUserInfoBean publicUserInfoBean = PublicUserInfoBean.fromUserInfoBean( userInfo, pwmApplication.getConfig(), locale, macroMachine );
            sendData.put( "userInfo", publicUserInfoBean );
        }

        final String jsonRequestBody = JsonUtil.serializeMap( sendData );
        try
        {
            final String responseBody = RestClientHelper.makeOutboundRestWSCall( pwmApplication, locale, restURL,
                    jsonRequestBody );
            final Map<String, Object> responseMap = JsonUtil.deserialize( responseBody,
                    new TypeToken<Map<String, Object>>()
                    {
                    }
            );
            if ( responseMap.containsKey( REST_RESPONSE_KEY_ERROR ) && Boolean.parseBoolean( responseMap.get(
                    REST_RESPONSE_KEY_ERROR ).toString() ) )
            {
                if ( responseMap.containsKey( REST_RESPONSE_KEY_ERROR_MSG ) )
                {
                    final String errorMessage = responseMap.get( REST_RESPONSE_KEY_ERROR_MSG ).toString();
                    LOGGER.trace( () -> "external web service reported error: " + errorMessage );
                    returnedErrors.add( new ErrorInformation( PwmError.PASSWORD_CUSTOM_ERROR, errorMessage, errorMessage, null ) );
                }
                else
                {
                    LOGGER.trace( () -> "external web service reported error without specifying an errorMessage" );
                    returnedErrors.add( new ErrorInformation( PwmError.PASSWORD_CUSTOM_ERROR ) );
                }
            }
            else
            {
                LOGGER.trace( () -> "external web service did not report an error" );
            }

        }
        catch ( PwmOperationalException e )
        {
            final String errorMsg = "error executing external rule REST call: " + e.getMessage();
            LOGGER.error( errorMsg );
            if ( haltOnError )
            {
                throw new PwmUnrecoverableException( e.getErrorInformation(), e );
            }
            throw new IllegalStateException( "http response error code: " + e.getMessage() );
        }
        return returnedErrors;
    }

    public static List<ErrorInformation> basicSyntaxRuleChecks(
            final String password,
            final PwmPasswordPolicy policy,
            final UserInfo userInfo
    )
            throws PwmUnrecoverableException
    {
        final List<ErrorInformation> errorList = new ArrayList<>();
        final RuleCheckerHelper ruleCheckerHelper = new RuleCheckerHelper( policy, userInfo, policy.getRuleHelper(), new PasswordCharCounter( password ) );

        for ( final RuleChecker ruleChecker : BASIC_RULE_CHECKERS )
        {
            errorList.addAll( ruleChecker.test( password, ruleCheckerHelper ) );
        }

        return errorList;
    }


    private interface RuleChecker
    {
        List<ErrorInformation> test(
                String password,
                RuleCheckerHelper ruleCheckerHelper
        )
                throws PwmUnrecoverableException;
    }

    @Data
    @AllArgsConstructor
    private static class RuleCheckerHelper
    {
        private PwmPasswordPolicy policy;
        private UserInfo userInfo;
        private PasswordRuleHelper ruleHelper;
        private PasswordCharCounter charCounter;
    }

    private static final List<RuleChecker> BASIC_RULE_CHECKERS = Collections.unmodifiableList( Arrays.asList(
            new MinimumLengthRuleChecker(),
            new MaximumLengthRuleChecker(),
            new NumericLimitsRuleChecker(),
            new AlphaLimitsRuleChecker(),
            new CasingLimitsRuleChecker(),
            new SpecialLimitsRuleChecker(),
            new UniqueCharRuleChecker(),
            new CharSequenceRuleChecker(),
            new ActiveDirectoryRuleChecker()
    ) );

    private static class MinimumLengthRuleChecker implements RuleChecker
    {
        @Override
        public List<ErrorInformation> test( final String password, final RuleCheckerHelper ruleCheckerHelper )
                throws PwmUnrecoverableException
        {
            //Check minimum length
            if ( password.length() < ruleCheckerHelper.getRuleHelper().readIntValue( PwmPasswordRule.MinimumLength ) )
            {
                return Collections.singletonList( new ErrorInformation( PwmError.PASSWORD_TOO_SHORT ) );
            }
            return Collections.emptyList();
        }
    }

    private static class MaximumLengthRuleChecker implements RuleChecker
    {
        @Override
        public List<ErrorInformation> test( final String password, final RuleCheckerHelper ruleCheckerHelper )
                throws PwmUnrecoverableException
        {
            //Check maximum length
            {
                final int passwordMaximumLength = ruleCheckerHelper.getRuleHelper().readIntValue( PwmPasswordRule.MaximumLength );

                if ( passwordMaximumLength > 0 && password.length() > passwordMaximumLength )
                {
                    return Collections.singletonList( new ErrorInformation( PwmError.PASSWORD_TOO_LONG ) );
                }
            }
            return Collections.emptyList();
        }
    }

    private static class NumericLimitsRuleChecker implements RuleChecker
    {
        @Override
        public List<ErrorInformation> test( final String password, final RuleCheckerHelper ruleCheckerHelper )
                throws PwmUnrecoverableException
        {
            //check number of numeric characters
            final List<ErrorInformation> errorList = new ArrayList<>();
            final PasswordRuleHelper ruleHelper = ruleCheckerHelper.getRuleHelper();
            final PasswordCharCounter charCounter = ruleCheckerHelper.getCharCounter();
            {
                final int numberOfNumericChars = charCounter.getNumericCharCount();
                if ( ruleHelper.readBooleanValue( PwmPasswordRule.AllowNumeric ) )
                {
                    if ( numberOfNumericChars < ruleHelper.readIntValue( PwmPasswordRule.MinimumNumeric ) )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_NOT_ENOUGH_NUM ) );
                    }

                    final int maxNumeric = ruleHelper.readIntValue( PwmPasswordRule.MaximumNumeric );
                    if ( maxNumeric > 0 && numberOfNumericChars > maxNumeric )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_NUMERIC ) );
                    }

                    if ( !ruleHelper.readBooleanValue(
                            PwmPasswordRule.AllowFirstCharNumeric ) && charCounter.isFirstNumeric() )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_FIRST_IS_NUMERIC ) );
                    }

                    if ( !ruleHelper.readBooleanValue(
                            PwmPasswordRule.AllowLastCharNumeric ) && charCounter.isLastNumeric() )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_LAST_IS_NUMERIC ) );
                    }
                }
                else
                {
                    if ( numberOfNumericChars > 0 )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_NUMERIC ) );
                    }
                }
            }
            return Collections.unmodifiableList( errorList );
        }
    }

    private static class CasingLimitsRuleChecker implements RuleChecker
    {
        @Override
        public List<ErrorInformation> test( final String password, final RuleCheckerHelper ruleCheckerHelper )
                throws PwmUnrecoverableException
        {
            final List<ErrorInformation> errorList = new ArrayList<>();
            final PasswordRuleHelper ruleHelper = ruleCheckerHelper.getRuleHelper();
            final PasswordCharCounter charCounter = ruleCheckerHelper.getCharCounter();

            //check number of upper characters
            {
                final int numberOfUpperChars = charCounter.getUpperCharCount();
                if ( numberOfUpperChars < ruleHelper.readIntValue( PwmPasswordRule.MinimumUpperCase ) )
                {
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_NOT_ENOUGH_UPPER ) );
                }

                final int maxUpper = ruleHelper.readIntValue( PwmPasswordRule.MaximumUpperCase );
                if ( maxUpper > 0 && numberOfUpperChars > maxUpper )
                {
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_UPPER ) );
                }
            }

            //check number of lower characters
            {
                final int numberOfLowerChars = charCounter.getLowerCharCount();
                if ( numberOfLowerChars < ruleHelper.readIntValue( PwmPasswordRule.MinimumLowerCase ) )
                {
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_NOT_ENOUGH_LOWER ) );
                }

                final int maxLower = ruleHelper.readIntValue( PwmPasswordRule.MaximumLowerCase );
                if ( maxLower > 0 && numberOfLowerChars > maxLower )
                {
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_UPPER ) );
                }
            }
            return Collections.unmodifiableList( errorList );
        }
    }

    private static class AlphaLimitsRuleChecker implements RuleChecker
    {
        @Override
        public List<ErrorInformation> test( final String password, final RuleCheckerHelper ruleCheckerHelper )
                throws PwmUnrecoverableException
        {
            final List<ErrorInformation> errorList = new ArrayList<>();
            final PasswordRuleHelper ruleHelper = ruleCheckerHelper.getRuleHelper();
            final PasswordCharCounter charCounter = ruleCheckerHelper.getCharCounter();

            //check number of alpha characters
            {
                final int numberOfAlphaChars = charCounter.getAlphaCharCount();
                if ( numberOfAlphaChars < ruleHelper.readIntValue( PwmPasswordRule.MinimumAlpha ) )
                {
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_NOT_ENOUGH_ALPHA ) );
                }

                final int maxAlpha = ruleHelper.readIntValue( PwmPasswordRule.MaximumAlpha );
                if ( maxAlpha > 0 && numberOfAlphaChars > maxAlpha )
                {
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_ALPHA ) );
                }
            }

            //check number of non-alpha characters
            {
                final int numberOfNonAlphaChars = charCounter.getNonAlphaCharCount();

                if ( ruleHelper.readBooleanValue( PwmPasswordRule.AllowNonAlpha ) )
                {
                    if ( numberOfNonAlphaChars < ruleHelper.readIntValue( PwmPasswordRule.MinimumNonAlpha ) )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_NOT_ENOUGH_NONALPHA ) );
                    }

                    final int maxNonAlpha = ruleHelper.readIntValue( PwmPasswordRule.MaximumNonAlpha );
                    if ( maxNonAlpha > 0 && numberOfNonAlphaChars > maxNonAlpha )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_NONALPHA ) );
                    }
                }
                else
                {
                    if ( numberOfNonAlphaChars > 0 )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_NONALPHA ) );
                    }
                }
            }
            return Collections.unmodifiableList( errorList );
        }
    }

    private static class SpecialLimitsRuleChecker implements RuleChecker
    {
        @Override
        public List<ErrorInformation> test( final String password, final RuleCheckerHelper ruleCheckerHelper )
                throws PwmUnrecoverableException
        {
            final List<ErrorInformation> errorList = new ArrayList<>();
            final PasswordRuleHelper ruleHelper = ruleCheckerHelper.getRuleHelper();
            final PasswordCharCounter charCounter = ruleCheckerHelper.getCharCounter();

            //check number of special characters
            {
                final int numberOfSpecialChars = charCounter.getSpecialCharsCount();
                if ( ruleHelper.readBooleanValue( PwmPasswordRule.AllowSpecial ) )
                {
                    if ( numberOfSpecialChars < ruleHelper.readIntValue( PwmPasswordRule.MinimumSpecial ) )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_NOT_ENOUGH_SPECIAL ) );
                    }

                    final int maxSpecial = ruleHelper.readIntValue( PwmPasswordRule.MaximumSpecial );
                    if ( maxSpecial > 0 && numberOfSpecialChars > maxSpecial )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_SPECIAL ) );
                    }

                    if ( !ruleHelper.readBooleanValue(
                            PwmPasswordRule.AllowFirstCharSpecial ) && charCounter.isFirstSpecial() )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_FIRST_IS_SPECIAL ) );
                    }

                    if ( !ruleHelper.readBooleanValue(
                            PwmPasswordRule.AllowLastCharSpecial ) && charCounter.isLastSpecial() )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_LAST_IS_SPECIAL ) );
                    }
                }
                else
                {
                    if ( numberOfSpecialChars > 0 )
                    {
                        errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_SPECIAL ) );
                    }
                }
            }

            return Collections.unmodifiableList( errorList );
        }
    }

    private static class CharSequenceRuleChecker implements RuleChecker
    {
        @Override
        public List<ErrorInformation> test( final String password, final RuleCheckerHelper ruleCheckerHelper )
                throws PwmUnrecoverableException
        {
            final List<ErrorInformation> errorList = new ArrayList<>();
            final PasswordRuleHelper ruleHelper = ruleCheckerHelper.getRuleHelper();
            final PasswordCharCounter charCounter = ruleCheckerHelper.getCharCounter();

            //Check maximum character repeats (sequential)
            {
                final int maxSequentialRepeat = ruleHelper.readIntValue( PwmPasswordRule.MaximumSequentialRepeat );
                if ( maxSequentialRepeat > 0 && charCounter.getSequentialRepeatedChars() > maxSequentialRepeat )
                {
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_REPEAT ) );
                }

                //Check maximum character repeats (overall)
                final int maxRepeat = ruleHelper.readIntValue( PwmPasswordRule.MaximumRepeat );
                if ( maxRepeat > 0 && charCounter.getRepeatedChars() > maxRepeat )
                {
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_REPEAT ) );
                }
            }

            // check consecutive characters
            {
                final int maximumConsecutive = ruleHelper.readIntValue( PwmPasswordRule.MaximumConsecutive );
                if ( PwmPasswordRuleUtil.tooManyConsecutiveChars( password, maximumConsecutive ) )
                {
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_TOO_MANY_CONSECUTIVE ) );
                }
            }

            return Collections.unmodifiableList( errorList );
        }
    }

    private static class UniqueCharRuleChecker implements RuleChecker
    {
        @Override
        public List<ErrorInformation> test( final String password, final RuleCheckerHelper ruleCheckerHelper )
                throws PwmUnrecoverableException
        {
            final List<ErrorInformation> errorList = new ArrayList<>();
            final PasswordRuleHelper ruleHelper = ruleCheckerHelper.getRuleHelper();
            final PasswordCharCounter charCounter = ruleCheckerHelper.getCharCounter();

            //Check minimum unique character
            {
                final int minUnique = ruleHelper.readIntValue( PwmPasswordRule.MinimumUnique );
                if ( minUnique > 0 && charCounter.getUniqueChars() < minUnique )
                {
                    errorList.add( new ErrorInformation( PwmError.PASSWORD_NOT_ENOUGH_UNIQUE ) );
                }
            }

            return Collections.unmodifiableList( errorList );
        }
    }

    private static class ActiveDirectoryRuleChecker implements RuleChecker
    {
        @Override
        public List<ErrorInformation> test( final String password, final RuleCheckerHelper ruleCheckerHelper )
                throws PwmUnrecoverableException
        {
            final List<ErrorInformation> errorList = new ArrayList<>();
            final PasswordRuleHelper ruleHelper = ruleCheckerHelper.getRuleHelper();
            final PasswordCharCounter charCounter = ruleCheckerHelper.getCharCounter();

            // check ad-complexity
            {
                final ADPolicyComplexity complexityLevel = ruleHelper.getADComplexityLevel();
                if ( complexityLevel == ADPolicyComplexity.AD2003 || complexityLevel == ADPolicyComplexity.AD2008 )
                {
                    final int maxGroupViolations = ruleHelper.readIntValue( PwmPasswordRule.ADComplexityMaxViolations );
                    errorList.addAll( PwmPasswordRuleUtil.checkPasswordForADComplexity(
                            complexityLevel,
                            ruleCheckerHelper.getUserInfo(),
                            password,
                            charCounter,
                            maxGroupViolations ) );
                }
            }

            return Collections.unmodifiableList( errorList );
        }
    }
}
