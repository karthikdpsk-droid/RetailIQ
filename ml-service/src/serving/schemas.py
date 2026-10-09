"""Pydantic contracts matching the trained pipeline and inventory module."""
from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import Annotated, Literal

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, field_validator, model_validator

StrictPositiveInt = Annotated[int, Field(strict=True, ge=1)]
BinaryFlag = Annotated[int, Field(strict=True, ge=0, le=1)]
NonNegativeFloat = Annotated[float, Field(ge=0, allow_inf_nan=False)]
OptionalNonNegativeFloat = Annotated[float | None, Field(ge=0, allow_inf_nan=False)]
FiniteFloat = Annotated[float, Field(allow_inf_nan=False)]


class ForecastRequest(BaseModel):
    """One target-date row using exactly the serialized model's input columns."""

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    forecast_date: date = Field(description="Date being forecast; one-day-ahead target date")
    prediction_cutoff: date = Field(description="Cutoff after the prior date; prevents claiming a longer horizon")
    store_nbr: StrictPositiveInt
    family: str = Field(min_length=1)
    onpromotion: NonNegativeFloat
    city: str = Field(min_length=1)
    state: str = Field(min_length=1)
    type: str = Field(min_length=1, description="Store type category; unseen categories are accepted by the model")
    cluster: Annotated[int | None, Field(strict=True, ge=1)] = Field(description="Existing store attribute; not a DBSCAN label")
    dcoilwtico: FiniteFloat | None = None
    is_holiday_event: BinaryFlag
    year: Annotated[int, Field(strict=True, ge=1)]
    month: Annotated[int, Field(strict=True, ge=1, le=12)]
    day: Annotated[int, Field(strict=True, ge=1, le=31)]
    day_of_week: Annotated[int, Field(strict=True, ge=0, le=6)]
    week_of_year: Annotated[int, Field(strict=True, ge=1, le=53)]
    quarter: Annotated[int, Field(strict=True, ge=1, le=4)]
    is_weekend: BinaryFlag
    has_promotion: BinaryFlag
    sales_lag_1: OptionalNonNegativeFloat
    sales_lag_7: OptionalNonNegativeFloat
    sales_lag_14: OptionalNonNegativeFloat
    sales_lag_28: OptionalNonNegativeFloat
    sales_rolling_mean_7: OptionalNonNegativeFloat
    sales_rolling_mean_14: OptionalNonNegativeFloat
    sales_rolling_mean_28: OptionalNonNegativeFloat
    sales_rolling_std_7: OptionalNonNegativeFloat

    @field_validator("family", "city", "state", "type")
    @classmethod
    def non_empty_category(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("must not be empty")
        return value

    @model_validator(mode="after")
    def calendar_fields_match_forecast_date(self):
        target = self.forecast_date
        if target != self.prediction_cutoff + timedelta(days=1):
            raise ValueError("forecast_date must be one day after prediction_cutoff")
        if (self.year, self.month, self.day) != (target.year, target.month, target.day):
            raise ValueError("year, month, and day must match forecast_date")
        if self.day_of_week != target.weekday():
            raise ValueError("day_of_week must match forecast_date (Monday=0)")
        if self.week_of_year != target.isocalendar().week:
            raise ValueError("week_of_year must match forecast_date")
        if self.quarter != (target.month - 1) // 3 + 1:
            raise ValueError("quarter must match forecast_date")
        if self.is_weekend != int(target.weekday() >= 5):
            raise ValueError("is_weekend must match forecast_date")
        if self.has_promotion != int(self.onpromotion > 0):
            raise ValueError("has_promotion must match onpromotion")
        return self


class ForecastResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    store_nbr: int
    family: str
    forecast_date: date
    forecast_demand: Annotated[float, Field(ge=0, allow_inf_nan=False)]
    forecast_horizon_days: Literal[1] = 1
    forecast_type: Literal["point_forecast"] = "point_forecast"
    model_version: str


class HistoricalDemandPoint(BaseModel):
    model_config = ConfigDict(extra="forbid")
    date: date
    sales: NonNegativeFloat


class InventoryOptimizeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    store_nbr: StrictPositiveInt
    family: str = Field(min_length=1)
    forecast_date: date | None = None
    forecast_demand: OptionalNonNegativeFloat = None
    prediction_cutoff: date
    historical_demand: list[HistoricalDemandPoint] = Field(default_factory=list)
    lead_time_days: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    review_period_days: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    service_level: Annotated[float | None, Field(gt=0, lt=1, allow_inf_nan=False)] = None
    current_inventory: OptionalNonNegativeFloat = Field(
        default=None, validation_alias=AliasChoices("current_inventory", "on_hand_inventory"),
        description="On-hand inventory in sales-equivalent units; never inferred.",
    )
    on_order_inventory: OptionalNonNegativeFloat = None
    backorder_quantity: OptionalNonNegativeFloat = None

    @field_validator("family")
    @classmethod
    def non_empty_family(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("must not be empty")
        return value

    @model_validator(mode="after")
    def forecast_after_cutoff(self):
        if self.forecast_date is not None and self.forecast_date != self.prediction_cutoff + timedelta(days=1):
            raise ValueError("forecast_date must be one day after prediction_cutoff")
        return self


class InventoryOptimizeResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    store_nbr: int | None = None
    family: str | None = None
    forecast_date: date | None = None
    forecast_demand: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    demand_variability: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    lead_time: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    review_period: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    service_level: Annotated[float | None, Field(gt=0, lt=1, allow_inf_nan=False)] = None
    safety_stock: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    reorder_point: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    target_stock: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    current_inventory: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    on_order_inventory: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    backorder_quantity: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    inventory_position: FiniteFloat | None = None
    recommended_order_quantity: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    inventory_position_basis: str | None = None
    stock_status: str
    model_version: str
    calculation_timestamp: datetime


class HealthResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    status: Literal["ok", "degraded"]
    service: Literal["retailiq-ml-service"] = "retailiq-ml-service"
    model_version: str | None


class ErrorResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    status: Literal["error"] = "error"
    message: str
    request_id: str | None = None
    details: list[dict[str, str]] | None = None
